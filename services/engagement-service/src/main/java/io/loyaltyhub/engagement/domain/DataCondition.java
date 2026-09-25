package io.loyaltyhub.engagement.domain;

import io.loyaltyhub.common.condition.ConditionRules;
import io.loyaltyhub.common.condition.TypedCast;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Condizione opzionale di una regola di notifica (docs/servizi/engagement-service.md §2): stesso formato delle
 * condizioni di campagna (docs/03 §3.3) ma sul solo spazio {@code data.*} del fatto. La semantica è quella del motore di
 * campaign, riportata qui (nessuna chiamata tra servizi, CLAUDE.md §1.3): gruppo {@code {op: all|any|not, rules}} o
 * foglia {@code {field, cmp, value}}; campo assente → foglia falsa (tranne {@code nexists}); tipi incompatibili → falsa,
 * mai eccezione; su array ({@code items[*].x}) vero se almeno un elemento soddisfa. Un campo fuori da {@code data.*} è
 * sempre assente. Condizione {@code null} o vuota = sempre vera. Cast dei valori: {@link TypedCast} di lh-common
 * (Q-215), lo stesso degli altri tre valutatori.
 */
public final class DataCondition {

    public static final Set<String> COMPARATORS = Set.copyOf(TypedCast.COMPARATORS);
    private static final Object ABSENT = new Object();

    /** Solo {@code data.*}: gli altri spazi non esistono per una regola di notifica. */
    private static final ConditionRules.Schema SCHEMA = new ConditionRules.Schema() {
        @Override
        public String fieldProblem(String field) {
            return field.startsWith("data.") && field.length() > "data.".length()
                    ? null : "campo \"" + field + "\": le regole di notifica leggono solo data.*";
        }
    };

    private DataCondition() {
    }

    /** Vero se {@code condition} è soddisfatta dal {@code data} del fatto. Condizione {@code null} o {@code {}} = vera. */
    public static boolean matches(JsonNode condition, JsonNode data) {
        if (condition == null || condition.isNull() || condition.isMissingNode()
                || (condition.isObject() && condition.isEmpty())) {
            return true;
        }
        return eval(condition, data);
    }

    /**
     * Problemi di forma della condizione (per la validazione della gestione, 422 {@code RULE_INVALID} sul campo
     * {@code condition}). Vuoto = valida. Regole comuni di lh-common ({@link ConditionRules}): operatore di gruppo
     * sconosciuto, {@code any} senza regole, foglia senza {@code field} o {@code cmp}, comparatore sconosciuto, valore
     * mancante o di forma sbagliata (Q-179 decisa, conservativa). I tipi dei campi {@code data.*} non sono noti qui.
     */
    public static List<String> problems(JsonNode condition) {
        List<String> out = new ArrayList<>();
        for (ConditionRules.Issue i : ConditionRules.validate(condition, "condition", SCHEMA)) {
            out.add(i.path() + ": " + i.message());
        }
        return out;
    }

    /**
     * Gruppo o foglia (Q-179 decisa, conservativa): operatore di gruppo sconosciuto o mancante → falso; {@code all}
     * senza regole → vero, {@code any} senza regole → falso; foglia senza {@code field} o {@code cmp} → falsa.
     */
    private static boolean eval(JsonNode node, JsonNode data) {
        if (node == null || !node.isObject()) {
            return false;
        }
        if (ConditionRules.isGroup(node)) {
            JsonNode rules = node.path("rules");
            String op = node.path("op").isString() ? node.path("op").asString() : "";
            return switch (op) {
                case "all" -> allOf(rules, data);
                case "any" -> anyOf(rules, data);
                case "not" -> !allOf(rules, data);
                default -> false;
            };
        }
        String field = node.path("field").isString() ? node.path("field").asString() : null;
        String cmp = node.path("cmp").isString() ? node.path("cmp").asString() : null;
        if (field == null || field.isBlank() || cmp == null) {
            return false;
        }
        return compare(cmp, resolve(field, data), node.get("value"));
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
        return false; // any senza regole → falso (Q-179, Q-222)
    }

    // ---------- comparatori (come campaign, docs/03 §3.3) ----------

    /**
     * Campo assente o {@code null} → falsa (tranne {@code nexists}); su array vero se almeno un elemento soddisfa;
     * {@code contains/ncontains} guardano l'array intero (appartenenza). Lo scalare usa il cast tipizzato comune di
     * lh-common ({@link TypedCast}, Q-215/Q-216 decise), identico a campaign, member e gamification: il valore della
     * regola è convertito nel tipo del dato (una stringa numerica nel dato resta testo); conversione fallita o
     * comparatore sconosciuto → falsa, negazioni comprese; {@code between} con estremi inclusi.
     */
    private static boolean compare(String cmp, Object actual, JsonNode value) {
        if ("exists".equals(cmp)) {
            return actual != ABSENT;
        }
        if ("nexists".equals(cmp)) {
            return actual == ABSENT;
        }
        if (actual == ABSENT) {
            return false;
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

    // ---------- risoluzione dei campi ----------

    private static Object resolve(String field, JsonNode data) {
        if (!field.startsWith("data.") || data == null || data.isNull()) {
            return ABSENT;
        }
        return navigate(data, field.substring("data.".length()));
    }

    /** Naviga {@code a.b}, {@code arr[*].c}; ritorna scalare, {@code List} o ABSENT. */
    private static Object navigate(JsonNode node, String path) {
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
            return toObject(current.get(0));
        }
        List<Object> values = new ArrayList<>();
        for (JsonNode n : current) {
            values.add(toObject(n));
        }
        return values;
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
