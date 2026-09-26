package io.loyaltyhub.campaign.engine;

import io.loyaltyhub.common.condition.ConditionRules;
import io.loyaltyhub.common.condition.TypedCast;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Valuta l'albero di condizioni di una campagna (docs/03 §3.3) sullo spazio {@code data/member/context/history}.
 * Campo assente o {@code null} → foglia falsa (tranne {@code nexists}); tipi incompatibili → falsa, mai eccezione.
 * Su array ({@code items[*].x}): vero se almeno un elemento soddisfa. Cast dei valori: {@link TypedCast} di lh-common,
 * identico in member, gamification ed engagement (Q-215).
 */
public final class ConditionEvaluator {

    /** {@code Europe/Rome} per {@code context.dayOfWeek/hour/date} (docs/03 §3.3). */
    private static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private static final Object ABSENT = new Object();

    private final EvalAction action;
    private final MemberSnapshot member;
    private final Counters counters;

    public ConditionEvaluator(EvalAction action, MemberSnapshot member, Counters counters) {
        this.action = action;
        this.member = member;
        this.counters = counters;
    }

    public record Result(boolean pass, List<Evaluation.FailedCondition> failed) {
    }

    /**
     * Validazione di salvataggio delle condizioni (422 {@code CONDITION_INVALID}, Q-215/Q-219/Q-222/Q-223/Q-224): regole
     * comuni di lh-common ({@link ConditionRules}) più il tipo dei campi che il motore calcola da sé
     * ({@code member.*} tranne gli attributi custom, {@code context.*}, {@code history.*}).
     */
    // SPEC-GAP: Q-215 — i tipi di data.* (catalogo di ingestion GET /v1/event-types/{code}/fields) e di
    // member.attributes.* (definizioni di member) non sono raggiungibili senza chiamate sincrone (CLAUDE.md §1.3): li
    // verifica il costruttore del backoffice (web/lib/campaign/conditions.ts) con lo stesso cast; qui solo la forma.
    public static List<ConditionRules.Issue> validate(JsonNode conditions) {
        return ConditionRules.validate(conditions, "conditions", SCHEMA);
    }

    private static final ConditionRules.Schema SCHEMA = new ConditionRules.Schema() {
        @Override
        public TypedCast.Type declaredType(String field) {
            return switch (field) {
                case "member.tier", "member.status", "member.province", "context.source", "context.dayOfWeek" ->
                        TypedCast.Type.STRING;
                case "member.age", "member.registeredDaysAgo", "context.hour", "history.actionCount",
                     "history.daysSinceLastAction" -> TypedCast.Type.NUMBER;
                case "context.date" -> TypedCast.Type.DATE;
                default -> null;
            };
        }

        @Override
        public boolean isList(String field) {
            return "member.segments".equals(field) || "member.labels".equals(field);
        }
    };

    public Result evaluate(JsonNode node) {
        List<Evaluation.FailedCondition> failed = new ArrayList<>();
        if (node == null || node.isNull() || node.isMissingNode() || (node.isObject() && node.isEmpty())) {
            return new Result(true, failed); // nessuna condizione = sempre vero (Q-225)
        }
        boolean pass = eval(node, failed);
        return new Result(pass, failed);
    }

    /**
     * Gruppo {@code {op, rules}} o foglia. Decisioni conservative (docs/15): operatore di gruppo sconosciuto o mancante
     * → falso (Q-223); {@code any} senza regole → falso (Q-222); foglia senza {@code field} o senza {@code cmp} → falsa
     * (Q-224, Q-219). La validazione di salvataggio li rifiuta con 422 {@code CONDITION_INVALID}.
     */
    private boolean eval(JsonNode node, List<Evaluation.FailedCondition> failed) {
        if (node == null || !node.isObject()) {
            return false;
        }
        if (ConditionRules.isGroup(node)) {
            String op = node.path("op").isString() ? node.path("op").asString() : "";
            JsonNode rules = node.path("rules");
            return switch (op) {
                case "all" -> allOf(rules, failed);
                case "any" -> anyOf(rules, failed);
                case "not" -> !allOf(rules, new ArrayList<>());
                default -> false;
            };
        }
        return leaf(node, failed);
    }

    private boolean allOf(JsonNode rules, List<Evaluation.FailedCondition> failed) {
        boolean ok = true;
        if (rules != null) {
            for (JsonNode r : rules) {
                if (!eval(r, failed)) {
                    ok = false;
                }
            }
        }
        return ok;
    }

    private boolean anyOf(JsonNode rules, List<Evaluation.FailedCondition> failed) {
        if (rules == null || rules.isEmpty()) {
            return false; // Q-222: nessuna regola soddisfatta
        }
        List<Evaluation.FailedCondition> local = new ArrayList<>();
        for (JsonNode r : rules) {
            if (eval(r, local)) {
                return true;
            }
        }
        failed.addAll(local);
        return false;
    }

    private boolean leaf(JsonNode node, List<Evaluation.FailedCondition> failed) {
        String field = node.path("field").isString() ? node.path("field").asString() : null;
        String cmp = node.path("cmp").isString() ? node.path("cmp").asString() : null;
        JsonNode value = node.get("value");
        boolean named = field != null && !field.isBlank();
        Object actual = named ? resolve(field) : ABSENT;
        boolean ok = named && cmp != null && compare(cmp, actual, value);
        if (!ok) {
            Object expected = jsonToObject(value);
            failed.add(new Evaluation.FailedCondition(field, cmp, expected == ABSENT ? null : expected,
                    actual == ABSENT ? null : actual));
        }
        return ok;
    }

    // ---------- comparatori ----------

    /**
     * Campo assente → falsa (tranne {@code nexists}); su elenco vero se almeno un elemento soddisfa (docs/03 §3.3),
     * anche con {@code in/nin}; {@code contains/ncontains} guardano l'elenco intero (appartenenza). Il confronto sullo
     * scalare è il cast tipizzato comune di lh-common ({@link TypedCast}, Q-215/Q-216 decise): il valore della regola è
     * convertito nel tipo del dato, conversione fallita → falsa per ogni comparatore, negazioni comprese.
     */
    private boolean compare(String cmp, Object actual, JsonNode value) {
        if ("exists".equals(cmp)) {
            return actual != ABSENT;
        }
        if ("nexists".equals(cmp)) {
            return actual == ABSENT;
        }
        if (actual == ABSENT) {
            return false; // campo assente → foglia falsa
        }
        if (actual instanceof List<?> list) {
            if ("contains".equals(cmp)) {
                return TypedCast.listContains(list, value);
            }
            if ("ncontains".equals(cmp)) {
                return TypedCast.listNotContains(list, value);
            }
            for (Object el : list) {
                if (el != ABSENT && TypedCast.compare(cmp, el, value)) {
                    return true;
                }
            }
            return false;
        }
        return TypedCast.compare(cmp, actual, value);
    }

    // ---------- risoluzione dei campi ----------

    private Object resolve(String field) {
        int dot = field.indexOf('.');
        String space = dot < 0 ? field : field.substring(0, dot);
        String rest = dot < 0 ? "" : field.substring(dot + 1);
        return switch (space) {
            case "data" -> resolveData(rest);
            case "member" -> resolveMember(rest);
            case "context" -> resolveContext(rest);
            case "history" -> resolveHistory(rest);
            default -> ABSENT;
        };
    }

    private Object resolveData(String path) {
        if (action.data() == null) {
            return ABSENT;
        }
        return navigate(action.data(), path);
    }

    /** Naviga un path {@code a.b}, {@code arr[*].c} su un JsonNode; ritorna scalare, {@code List} o ABSENT. */
    private Object navigate(JsonNode node, String path) {
        List<JsonNode> current = new ArrayList<>();
        current.add(node);
        for (String seg : path.split("\\.")) {
            boolean wildcard = seg.endsWith("[*]");
            String key = wildcard ? seg.substring(0, seg.length() - 3) : seg;
            List<JsonNode> next = new ArrayList<>();
            for (JsonNode n : current) {
                JsonNode child = n.get(key);
                if (child == null || child.isNull()) {
                    continue;
                }
                if (wildcard && child.isArray()) {
                    child.forEach(next::add);
                } else {
                    next.add(child);
                }
            }
            current = next;
            if (current.isEmpty()) {
                return ABSENT;
            }
        }
        if (current.size() == 1) {
            return jsonToObject(current.get(0));
        }
        List<Object> values = new ArrayList<>();
        for (JsonNode n : current) {
            values.add(jsonToObject(n));
        }
        return values;
    }

    private Object resolveMember(String path) {
        if (member == null) {
            return ABSENT;
        }
        return switch (path) {
            case "tier" -> member.tier();
            case "status" -> member.status();
            case "segments" -> new ArrayList<Object>(member.segments());
            case "labels" -> new ArrayList<Object>(member.labels());
            // Età al giorno di business dell'azione (Europe/Rome): stessa base di simulazione e macchina del tempo,
            // mai l'orologio di sistema (AUD-BE-02). Con la data (fatti :1) è esatta; con il solo anno (member.*:2,
            // ADR-032) è l'età minima certa: anni compiuti di sicuro a quella data.
            case "age" -> age(action.time().atZone(ROME).toLocalDate());
            case "province" -> member.province() == null ? ABSENT : member.province();
            case "registeredDaysAgo" -> member.registeredAt() == null ? ABSENT
                    : (double) ChronoUnit.DAYS.between(member.registeredAt(), action.time());
            default -> {
                if (path.startsWith("attributes.") && member.attributes() != null) {
                    yield jsonToObject(member.attributes().get(path.substring("attributes.".length())));
                }
                yield ABSENT;
            }
        };
    }

    // SPEC-GAP: Q-366 — con il solo anno di nascita l'età vale gli anni compiuti di sicuro (prudente sui limiti d'età).
    private Object age(java.time.LocalDate day) {
        if (member.birthDate() != null) {
            return (double) member.birthDate().until(day).getYears();
        }
        if (member.birthYear() == null) {
            return ABSENT;
        }
        int years = day.getYear() - member.birthYear();
        boolean lastDayOfYear = day.getMonthValue() == 12 && day.getDayOfMonth() == 31;
        return (double) (lastDayOfYear ? years : years - 1);
    }

    private Object resolveContext(String path) {
        ZonedDateTime t = action.time().atZone(ROME);
        return switch (path) {
            case "source" -> action.source();
            case "dayOfWeek" -> t.getDayOfWeek().name().substring(0, 3); // MON..SUN
            case "hour" -> (double) t.getHour();
            case "date" -> t.toLocalDate().toString();
            default -> ABSENT;
        };
    }

    private Object resolveHistory(String path) {
        return switch (path) {
            case "actionCount" -> (double) counters.historyActionCount(action.memberId(), action.type());
            case "daysSinceLastAction" -> {
                long d = counters.historyDaysSinceLastAction(action.memberId(), action.type());
                yield d < 0 ? ABSENT : (double) d;
            }
            default -> ABSENT;
        };
    }

    // ---------- utilità ----------

    private static Object jsonToObject(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return ABSENT;
        }
        if (n.isNumber()) {
            return n.decimalValue(); // confronto con BigDecimal (Q-215), mai double
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        if (n.isArray()) {
            List<Object> list = new ArrayList<>();
            n.forEach(e -> {
                Object o = jsonToObject(e);
                if (o != ABSENT) {
                    list.add(o); // elemento null = assente
                }
            });
            return list;
        }
        return n.isString() ? n.asString() : n; // oggetto: non scalare (nessun cast, Q-215)
    }
}
