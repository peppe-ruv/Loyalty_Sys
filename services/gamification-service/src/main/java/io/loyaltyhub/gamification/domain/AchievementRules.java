package io.loyaltyhub.gamification.domain;

import io.loyaltyhub.common.condition.ConditionRules;
import io.loyaltyhub.common.condition.TypedCast;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;

/**
 * Regole pure degli obiettivi (docs/03 §8): chiave di periodo, filtro sui dati dell'azione e avanzamento per metrica.
 * Senza stato né Spring: il servizio legge il progresso, chiama {@link #advance} e salva.
 */
public final class AchievementRules {

    public static final ZoneId ZONE = ZoneId.of("Europe/Rome");

    /** Progresso di un membro su un obiettivo in un periodo. */
    public record Progress(long value, List<String> distinctSeen, String lastUnitKey) {
        public static final Progress EMPTY = new Progress(0, List.of(), null);
    }

    private AchievementRules() {
    }

    /**
     * {@code EVER} → {@code EVER}; {@code MONTH} → {@code 2026-09}; {@code EDITION} → {@code ED-2026}. Le edizioni del
     * seed coincidono con l'anno solare e sono di wallet-service: qui si ricava dal giorno dell'azione (SPEC-GAP Q-59).
     */
    // SPEC-GAP: Q-59
    public static String periodKey(String period, Instant at) {
        LocalDate d = LocalDate.ofInstant(at, ZONE);
        return switch (period) {
            case "MONTH" -> String.format("%d-%02d", d.getYear(), d.getMonthValue());
            case "EDITION" -> "ED-" + d.getYear();
            default -> "EVER";
        };
    }

    /** Unità della serie: giorno ({@code 2026-09-24}) o settimana ISO ({@code 2026-W39}). */
    public static String unitKey(String unit, Instant at) {
        LocalDate d = LocalDate.ofInstant(at, ZONE);
        return "WEEK".equals(unit) ? weekKey(d) : d.toString();
    }

    private static String previousUnitKey(String unit, Instant at) {
        LocalDate d = LocalDate.ofInstant(at, ZONE);
        return "WEEK".equals(unit) ? weekKey(d.minusWeeks(1)) : d.minusDays(1).toString();
    }

    private static String weekKey(LocalDate d) {
        return String.format("%d-W%02d", d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    /**
     * Filtro opzionale sui dati dell'azione (docs/03 §8): albero di condizioni con la grammatica di docs/03 §3.3
     * ristretta a {@code data.*}, es. {@code {"op":"all","rules":[{"field":"data.amount","cmp":"gte","value":50}]}}.
     * Gruppi {@code all/any/not} annidabili; comparatori {@code eq, neq, gt, gte, lt, lte, in, nin, contains, ncontains,
     * exists, nexists, between, startsWith}; campi su elenco {@code items[*].x} veri se almeno un elemento soddisfa.
     * Campo assente o {@code null} → foglia falsa (tranne {@code nexists}); tipi incompatibili → falsa, mai eccezione.
     * Stessa semantica del motore campagne ({@code ConditionEvaluator} di campaign-service) e stesso cast tipizzato
     * ({@link TypedCast} di lh-common, Q-215). Nessun filtro → passa.
     */
    /**
     * Validazione di salvataggio del filtro (422 {@code CONDITION_INVALID}; Q-295 decisa e Q-219/Q-222/Q-223/Q-224):
     * regole comuni di lh-common ({@link ConditionRules}). Unico tipo dichiarato noto qui: il campo sommato da
     * {@code SUM} ({@code sumField}) è numerico, quindi una foglia sullo stesso campo deve avere un valore numerico
     * (Q-215). Gli altri {@code data.*} arrivano dal catalogo di ingestion, non raggiungibile senza chiamate sincrone.
     */
    public static List<ConditionRules.Issue> validateFilter(JsonNode filter, String sumField) {
        String sumPath = sumField == null || sumField.isBlank() ? null : dataPath(sumField.trim());
        return ConditionRules.validate(filter, "filter", new ConditionRules.Schema() {
            @Override
            public TypedCast.Type declaredType(String field) {
                return sumPath != null && sumPath.equals(dataPath(field)) ? TypedCast.Type.NUMBER : null;
            }
        });
    }

    public static boolean matches(JsonNode filter, JsonNode data) {
        if (filter == null || filter.isNull() || filter.isMissingNode() || (filter.isObject() && filter.isEmpty())) {
            return true; // nessun filtro = sempre vero
        }
        return eval(filter, data);
    }

    /**
     * Gruppo o foglia. {@code {"rules": …}} senza {@code op} = {@code all} (forma già accettata); operatore
     * sconosciuto → falso; {@code any} senza regole → falso (Q-222, come campaign); foglia senza {@code field} o
     * {@code cmp} → falsa (Q-224, Q-219).
     */
    private static boolean eval(JsonNode node, JsonNode data) {
        if (node == null || !node.isObject()) {
            return false;
        }
        if (node.has("op") || node.has("rules")) {
            JsonNode rules = node.path("rules");
            String op = !node.has("op") ? "all" : node.path("op").isString() ? node.path("op").asString() : "";
            return switch (op) {
                case "all" -> allOf(rules, data);
                case "any" -> anyOf(rules, data);
                case "not" -> !allOf(rules, data);
                default -> false; // Q-223
            };
        }
        return leaf(node, data);
    }

    private static boolean allOf(JsonNode rules, JsonNode data) {
        for (JsonNode r : rules) {
            if (!eval(r, data)) {
                return false;
            }
        }
        return true;
    }

    private static boolean anyOf(JsonNode rules, JsonNode data) {
        for (JsonNode r : rules) {
            if (eval(r, data)) {
                return true;
            }
        }
        return false; // any senza regole → falso (Q-222)
    }

    /**
     * Avanza il progresso per un'azione: {@code COUNT} +1, {@code SUM} + campo, {@code DISTINCT_TYPES} tipi distinti
     * tra quelli osservati, {@code STREAK} unità consecutive (stessa unità → invariato; un buco riparte da 1).
     */
    public static Progress advance(Achievement a, Progress p, String actionType, JsonNode data, Instant at) {
        return switch (a.metric()) {
            case "SUM" -> {
                JsonNode v = field(data, a.sumField() == null ? "" : a.sumField());
                long add = v == null || !v.isNumber() ? 0 : v.decimalValue().setScale(0, java.math.RoundingMode.DOWN).longValue();
                yield add <= 0 ? p : new Progress(p.value() + add, p.distinctSeen(), p.lastUnitKey());
            }
            case "DISTINCT_TYPES" -> {
                if (p.distinctSeen().contains(actionType)) {
                    yield p;
                }
                List<String> seen = new ArrayList<>(p.distinctSeen());
                seen.add(actionType);
                yield new Progress(seen.size(), List.copyOf(seen), p.lastUnitKey());
            }
            case "STREAK" -> {
                String unit = a.streakUnit() == null ? "DAY" : a.streakUnit();
                String key = unitKey(unit, at);
                if (key.equals(p.lastUnitKey())) {
                    yield p;
                }
                long value = previousUnitKey(unit, at).equals(p.lastUnitKey()) ? p.value() + 1 : 1;
                yield new Progress(value, p.distinctSeen(), key);
            }
            default -> new Progress(p.value() + 1, p.distinctSeen(), p.lastUnitKey());
        };
    }

    /** Campo di {@code data} per {@code SUM}: valore del path (senza {@code [*]}), {@code null} se assente. */
    static JsonNode field(JsonNode data, String path) {
        if (data == null || path.isBlank()) {
            return null;
        }
        JsonNode node = data;
        for (String seg : dataPath(path).split("\\.")) {
            node = node == null ? null : node.get(seg);
        }
        return node;
    }

    /** {@code data.amount} → {@code amount}; {@code purchase.completed.data.amount} (forma di docs/10) → {@code amount}. */
    private static String dataPath(String path) {
        String clean = path.startsWith("data.") ? path.substring(5) : path;
        int dot = clean.lastIndexOf("data.");
        return dot > 0 ? clean.substring(dot + 5) : clean;
    }

    // ---------- foglie: docs/03 §3.3, stessa semantica di ConditionEvaluator (campaign-service) ----------

    private static final Object ABSENT = new Object();

    private static boolean leaf(JsonNode node, JsonNode data) {
        String field = node.path("field").isString() ? node.path("field").asString() : null;
        String cmp = node.path("cmp").isString() ? node.path("cmp").asString() : null;
        if (field == null || field.isBlank() || cmp == null) {
            return false; // Q-224, Q-219
        }
        Object actual = data == null ? ABSENT : navigate(data, dataPath(field));
        return compare(cmp, actual, node.get("value"));
    }

    /** Naviga {@code a.b} o {@code arr[*].c}: scalare, {@code List} (più valori o array) o {@link #ABSENT}. */
    private static Object navigate(JsonNode root, String path) {
        List<JsonNode> current = new ArrayList<>();
        current.add(root);
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
            return toObject(current.get(0));
        }
        List<Object> values = new ArrayList<>();
        current.forEach(n -> values.add(toObject(n)));
        return values;
    }

    /**
     * Campo assente o {@code null} → falsa (tranne {@code nexists}); su elenco vero se almeno un elemento soddisfa, solo
     * {@code contains/ncontains} guardano l'elenco intero. Lo scalare usa il cast tipizzato comune di lh-common
     * ({@link TypedCast}, Q-215/Q-216 decise): comparatore sconosciuto → falso (Q-295 decisa), tipi incompatibili →
     * falso per ogni comparatore, negazioni comprese.
     */
    private static boolean compare(String cmp, Object actual, JsonNode value) {
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
                if (TypedCast.compare(cmp, el, value)) {
                    return true;
                }
            }
            return false;
        }
        return TypedCast.compare(cmp, actual, value);
    }

    private static Object toObject(JsonNode n) {
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
                Object o = toObject(e);
                if (o != ABSENT) {
                    list.add(o); // elemento null = assente
                }
            });
            return list;
        }
        return n.isString() ? n.asString() : n; // oggetto: non scalare (nessun cast, Q-215)
    }
}
