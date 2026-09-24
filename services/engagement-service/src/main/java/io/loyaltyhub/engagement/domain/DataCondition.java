package io.loyaltyhub.engagement.domain;

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
 * sempre assente. Condizione {@code null} o vuota = sempre vera.
 */
public final class DataCondition {

    public static final Set<String> COMPARATORS = Set.of("eq", "neq", "gt", "gte", "lt", "lte", "in", "nin", "contains",
            "ncontains", "exists", "nexists", "between", "startsWith");
    private static final Set<String> OPS = Set.of("all", "any", "not");
    private static final Object ABSENT = new Object();

    private DataCondition() {
    }

    /** Vero se {@code condition} è soddisfatta dal {@code data} del fatto. */
    public static boolean matches(JsonNode condition, JsonNode data) {
        return eval(condition, data);
    }

    /** Problemi di forma della condizione (per la validazione della gestione). Vuoto = valida. */
    public static List<String> problems(JsonNode condition) {
        List<String> out = new ArrayList<>();
        check(condition, out);
        return out;
    }

    private static void check(JsonNode node, List<String> out) {
        if (node == null || node.isNull() || (node.isObject() && node.isEmpty())) {
            return;
        }
        if (!node.isObject()) {
            out.add("ogni nodo della condizione è un oggetto");
            return;
        }
        if (node.has("op")) {
            String op = node.path("op").asString("");
            if (!OPS.contains(op)) {
                out.add("operatore di gruppo \"" + op + "\" (ammessi: all, any, not)");
            }
            if (!node.path("rules").isArray()) {
                out.add("il gruppo \"" + op + "\" richiede l'elenco rules");
                return;
            }
            node.path("rules").forEach(r -> check(r, out));
            return;
        }
        String field = node.path("field").asString("");
        String cmp = node.path("cmp").asString("eq");
        if (!field.startsWith("data.") || field.length() <= "data.".length()) {
            out.add("campo \"" + field + "\": le regole di notifica leggono solo data.*");
        }
        if (!COMPARATORS.contains(cmp)) {
            out.add("comparatore \"" + cmp + "\" sconosciuto");
        }
        if (!"exists".equals(cmp) && !"nexists".equals(cmp) && !node.has("value")) {
            out.add("la foglia su \"" + field + "\" richiede value");
        }
    }

    private static boolean eval(JsonNode node, JsonNode data) {
        if (node == null || node.isNull() || node.isEmpty()) {
            return true;
        }
        if (node.has("op")) {
            JsonNode rules = node.path("rules");
            return switch (node.path("op").asString("all")) {
                case "any" -> anyOf(rules, data);
                case "not" -> !allOf(rules, data);
                default -> allOf(rules, data);
            };
        }
        String field = node.path("field").asString(null);
        if (field == null) {
            return true;
        }
        return compare(node.path("cmp").asString("eq"), resolve(field, data), node.get("value"));
    }

    private static boolean allOf(JsonNode rules, JsonNode data) {
        boolean ok = true;
        if (rules != null) {
            for (JsonNode r : rules) {
                if (!eval(r, data)) {
                    ok = false;
                }
            }
        }
        return ok;
    }

    private static boolean anyOf(JsonNode rules, JsonNode data) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        for (JsonNode r : rules) {
            if (eval(r, data)) {
                return true;
            }
        }
        return false;
    }

    // ---------- comparatori (come campaign, docs/03 §3.3) ----------

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
        if (actual instanceof List<?> list && !isSetOp(cmp)) {
            for (Object el : list) {
                if (compareScalar(cmp, el, value)) {
                    return true;
                }
            }
            return false;
        }
        return compareScalar(cmp, actual, value);
    }

    private static boolean isSetOp(String cmp) {
        return "in".equals(cmp) || "nin".equals(cmp) || "contains".equals(cmp) || "ncontains".equals(cmp);
    }

    private static boolean compareScalar(String cmp, Object actual, JsonNode value) {
        return switch (cmp) {
            case "eq" -> equalsValue(actual, value);
            case "neq" -> !equalsValue(actual, value);
            case "gt" -> numeric(actual, value, c -> c > 0);
            case "gte" -> numeric(actual, value, c -> c >= 0);
            case "lt" -> numeric(actual, value, c -> c < 0);
            case "lte" -> numeric(actual, value, c -> c <= 0);
            case "in" -> inList(actual, value);
            case "nin" -> !inList(actual, value);
            case "contains" -> contains(actual, value);
            case "ncontains" -> !contains(actual, value);
            case "between" -> between(actual, value);
            case "startsWith" -> actual != null && value != null && actual.toString().startsWith(value.asString(""));
            default -> false;
        };
    }

    private static boolean equalsValue(Object actual, JsonNode value) {
        if (value == null || value.isNull() || actual == null || actual == ABSENT) {
            return false;
        }
        if (actual instanceof Number a && value.isNumber()) {
            return a.doubleValue() == value.asDouble();
        }
        if (actual instanceof Boolean a && value.isBoolean()) {
            return a == value.asBoolean();
        }
        return actual.toString().equals(value.asString(""));
    }

    private static boolean numeric(Object actual, JsonNode value, java.util.function.IntPredicate cmp) {
        Double a = toDouble(actual);
        if (a == null || value == null || !value.isNumber()) {
            return false;
        }
        return cmp.test(Double.compare(a, value.asDouble()));
    }

    private static boolean inList(Object actual, JsonNode value) {
        if (value == null || !value.isArray()) {
            return false;
        }
        if (actual instanceof List<?> list) {
            for (Object el : list) {
                if (inList(el, value)) {
                    return true;
                }
            }
            return false;
        }
        for (JsonNode v : value) {
            if (equalsValue(actual, v)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(Object actual, JsonNode value) {
        if (actual instanceof List<?> list) {
            for (Object el : list) {
                if (equalsValue(el, value)) {
                    return true;
                }
            }
            return false;
        }
        return actual != null && value != null && actual.toString().contains(value.asString(""));
    }

    private static boolean between(Object actual, JsonNode value) {
        Double a = toDouble(actual);
        if (a == null || value == null || !value.isArray() || value.size() < 2) {
            return false;
        }
        return a >= value.get(0).asDouble() && a <= value.get(1).asDouble();
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

    private static Double toDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Object toObject(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return ABSENT;
        }
        if (n.isNumber()) {
            return n.asDouble();
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        if (n.isArray()) {
            List<Object> list = new ArrayList<>();
            n.forEach(e -> list.add(toObject(e)));
            return list;
        }
        return n.asString("");
    }
}
