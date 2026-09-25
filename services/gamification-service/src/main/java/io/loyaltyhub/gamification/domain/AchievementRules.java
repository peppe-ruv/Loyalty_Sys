package io.loyaltyhub.gamification.domain;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

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
     * Stessa semantica del motore campagne ({@code ConditionEvaluator} di campaign-service). Nessun filtro → passa.
     */
    public static boolean matches(JsonNode filter, JsonNode data) {
        return eval(filter, data);
    }

    private static boolean eval(JsonNode node, JsonNode data) {
        if (node == null || node.isNull() || node.isEmpty()) {
            return true; // nessuna condizione = sempre vero
        }
        if (node.has("op") || node.has("rules")) { // {"rules": …} senza op = all (forma già accettata)
            JsonNode rules = node.path("rules");
            return switch (node.path("op").asString("all")) {
                case "any" -> anyOf(rules, data);
                case "not" -> !allOf(rules, data);
                default -> allOf(rules, data);
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
        if (rules.isEmpty()) {
            return true;
        }
        for (JsonNode r : rules) {
            if (eval(r, data)) {
                return true;
            }
        }
        return false;
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
        String field = node.path("field").asString(null);
        if (field == null) {
            return true;
        }
        String cmp = node.path("cmp").asString("eq");
        Object actual = data == null || field.isBlank() ? ABSENT : navigate(data, dataPath(field));
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
        // Su elenco: vero se almeno un elemento soddisfa; solo contains/ncontains guardano l'elenco intero.
        if (actual instanceof List<?> list && !"contains".equals(cmp) && !"ncontains".equals(cmp)) {
            for (Object el : list) {
                if (compareScalar(cmp, el, value)) {
                    return true;
                }
            }
            return false;
        }
        return compareScalar(cmp, actual, value);
    }

    /** Tipi incompatibili → falsa per ogni comparatore, negazioni comprese ({@code neq}, {@code nin}, {@code ncontains}). */
    private static boolean compareScalar(String cmp, Object actual, JsonNode value) {
        return switch (cmp) {
            case "eq" -> equalsValue(actual, value);
            case "neq" -> comparable(actual, value) && !equalsValue(actual, value);
            case "gt" -> numeric(actual, value, c -> c > 0);
            case "gte" -> numeric(actual, value, c -> c >= 0);
            case "lt" -> numeric(actual, value, c -> c < 0);
            case "lte" -> numeric(actual, value, c -> c <= 0);
            case "in" -> value != null && value.isArray() && inList(actual, value);
            case "nin" -> value != null && value.isArray() && allComparable(actual, value) && !inList(actual, value);
            case "contains" -> containsCompatible(actual, value) && contains(actual, value);
            case "ncontains" -> containsCompatible(actual, value) && !contains(actual, value);
            case "between" -> between(actual, value);
            case "startsWith" -> actual instanceof String s && value != null && value.isString() && s.startsWith(value.asString());
            // SPEC-GAP: Q-295 — comparatore sconosciuto: tra numeri vale come eq (comportamento conservato), altrimenti
            // falso; il motore campagne lo tratta sempre come falso.
            default -> actual instanceof Double && value != null && value.isNumber() && equalsValue(actual, value);
        };
    }

    /** Numero con numero (testo numerico compreso, come nel motore campagne), booleano con booleano, testo con testo. */
    // SPEC-GAP: Q-215 — testo numerico contro numero: confrontato come numero, come in campaign-service.
    private static boolean comparable(Object actual, JsonNode value) {
        if (actual == null || actual == ABSENT || value == null || value.isNull()) {
            return false;
        }
        if (value.isNumber()) {
            return toDouble(actual) != null;
        }
        if (value.isBoolean()) {
            return actual instanceof Boolean;
        }
        return value.isString() && actual instanceof String;
    }

    private static boolean allComparable(Object actual, JsonNode values) {
        for (JsonNode v : values) {
            if (!comparable(actual, v)) {
                return false;
            }
        }
        return true;
    }

    private static boolean equalsValue(Object actual, JsonNode value) {
        if (!comparable(actual, value)) {
            return false;
        }
        if (value.isNumber()) {
            return toDouble(actual) == value.asDouble();
        }
        if (value.isBoolean()) {
            return (Boolean) actual == value.asBoolean();
        }
        return actual.equals(value.asString());
    }

    /** {@code contains}/{@code ncontains}: su elenco (appartenenza) o su testo con un valore testuale (sottostringa). */
    private static boolean containsCompatible(Object actual, JsonNode value) {
        return value != null && !value.isNull() && (actual instanceof List<?> || (actual instanceof String && value.isString()));
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
        return actual instanceof String s && s.contains(value.asString());
    }

    private static boolean numeric(Object actual, JsonNode value, IntPredicate cmp) {
        Double a = toDouble(actual);
        return a != null && value != null && value.isNumber() && cmp.test(Double.compare(a, value.asDouble()));
    }

    private static boolean inList(Object actual, JsonNode values) {
        for (JsonNode v : values) {
            if (equalsValue(actual, v)) {
                return true;
            }
        }
        return false;
    }

    private static boolean between(Object actual, JsonNode value) {
        Double a = toDouble(actual);
        if (a == null || value == null || !value.isArray() || value.size() < 2) {
            return false;
        }
        return a >= value.get(0).asDouble() && a <= value.get(1).asDouble();
    }

    private static Double toDouble(Object o) {
        if (o instanceof Double d) {
            return d;
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
