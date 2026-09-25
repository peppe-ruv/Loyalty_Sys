package io.loyaltyhub.member.domain;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Criteri dei segmenti dinamici (docs/03 §10): stesso formato delle condizioni delle campagne (§3.3) — gruppo
 * {@code {op: all|any|not, rules}} o foglia {@code {field, cmp, value}} — sullo spazio {@code member.*} esteso con
 * {@code balance.PTS}, {@code lifetimeEarned.PTS}, {@code lastActivityDaysAgo}, {@code actions.<type>.count30d},
 * {@code purchases.amount90d}, {@code city}. Il prefisso {@code member.} è facoltativo. Campo assente → foglia falsa
 * (tranne {@code nexists}); tipi incompatibili → falsa, mai eccezione. Su una lista ({@code labels}) i comparatori
 * scalari sono veri se almeno un elemento soddisfa; {@code contains}/{@code in} lavorano sulla lista intera.
 * Classe pura, senza Spring: testata da {@code SegmentCriteriaTest}.
 * SPEC-GAP: Q-87 — oltre a {@code actions.<type>.count30d} è ammesso {@code actions.<type>.total} (il totale è già in
 * {@code member_stats.actions_by_type}); criteri vuoti non includono nessuno (un dinamico ne richiede almeno uno).
 */
public final class SegmentCriteria {

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private static final Object ABSENT = new Object();

    public static final Set<String> COMPARATORS = Set.of(
            "eq", "neq", "gt", "gte", "lt", "lte", "in", "nin", "contains", "ncontains", "exists", "nexists",
            "between", "startsWith");

    /** Campi semplici ammessi (senza prefisso); più {@code attributes.<k>} e {@code actions.<type>.count30d|total}. */
    public static final List<String> FIELDS = List.of(
            "tier", "status", "labels", "city", "age", "registeredDaysAgo", "balance.PTS", "lifetimeEarned.PTS",
            "lastActivityDaysAgo", "purchases.amount90d");

    /** Problema di validazione di un criterio: percorso nel JSON (es. {@code rules[1].cmp}) e messaggio in italiano. */
    public record Issue(String field, String message) {
    }

    private SegmentCriteria() {
    }

    // ---------- validazione ----------

    /** Verifica la forma dei criteri; lista vuota = validi. Criteri assenti o vuoti non sono ammessi per un DYNAMIC. */
    public static List<Issue> validate(JsonNode criteria) {
        List<Issue> issues = new ArrayList<>();
        if (criteria == null || criteria.isNull() || criteria.isMissingNode() || criteria.isEmpty()) {
            issues.add(new Issue("criteria", "servono criteri per un segmento dinamico"));
            return issues;
        }
        check(criteria, "criteria", issues);
        return issues;
    }

    private static void check(JsonNode node, String path, List<Issue> issues) {
        if (node == null || !node.isObject()) {
            issues.add(new Issue(path, "atteso un gruppo {op, rules} o una condizione {field, cmp, value}"));
            return;
        }
        if (node.has("op") || node.has("rules")) {
            String op = node.path("op").asString("");
            if (!Set.of("all", "any", "not").contains(op)) {
                issues.add(new Issue(path + ".op", "operatore di gruppo non valido: usa all, any o not"));
            }
            JsonNode rules = node.get("rules");
            if (rules == null || !rules.isArray() || rules.isEmpty()) {
                issues.add(new Issue(path + ".rules", "il gruppo deve contenere almeno una regola"));
                return;
            }
            for (int i = 0; i < rules.size(); i++) {
                check(rules.get(i), path + ".rules[" + i + "]", issues);
            }
            return;
        }
        String field = node.path("field").asString("");
        if (field.isBlank()) {
            issues.add(new Issue(path + ".field", "campo mancante"));
        } else if (!knownField(field)) {
            issues.add(new Issue(path + ".field", "campo non disponibile per i segmenti: " + field));
        }
        String cmp = node.path("cmp").asString("");
        if (!COMPARATORS.contains(cmp)) {
            issues.add(new Issue(path + ".cmp", "comparatore non valido: " + cmp));
            return;
        }
        JsonNode value = node.get("value");
        boolean needsValue = !cmp.equals("exists") && !cmp.equals("nexists");
        if (needsValue && (value == null || value.isNull())) {
            issues.add(new Issue(path + ".value", "valore mancante"));
        } else if ((cmp.equals("in") || cmp.equals("nin")) && !value.isArray()) {
            issues.add(new Issue(path + ".value", "per " + cmp + " serve un elenco di valori"));
        } else if (cmp.equals("between") && (!value.isArray() || value.size() != 2)) {
            issues.add(new Issue(path + ".value", "per between servono due valori [min, max]"));
        } else if (Set.of("gt", "gte", "lt", "lte").contains(cmp) && !value.isNumber()) {
            issues.add(new Issue(path + ".value", "per " + cmp + " serve un numero"));
        }
    }

    /** Campo ammesso nello spazio esteso (con o senza prefisso {@code member.}). */
    public static boolean knownField(String field) {
        String f = strip(field);
        if (FIELDS.contains(f)) {
            return true;
        }
        if (f.startsWith("attributes.") && f.length() > "attributes.".length()) {
            return true;
        }
        return actionField(f) != null;
    }

    // ---------- valutazione ----------

    /** Vero se il membro soddisfa i criteri alla data {@code asOf}. Criteri vuoti = nessun membro (mai "tutti"). */
    public static boolean matches(JsonNode criteria, SegmentFacts member, Instant asOf) {
        if (criteria == null || criteria.isNull() || criteria.isMissingNode() || criteria.isEmpty()) {
            return false;
        }
        return eval(criteria, member, asOf);
    }

    private static boolean eval(JsonNode node, SegmentFacts m, Instant asOf) {
        if (node.has("op") || node.has("rules")) {
            JsonNode rules = node.path("rules");
            return switch (node.path("op").asString("all")) {
                case "any" -> {
                    for (JsonNode r : rules) {
                        if (eval(r, m, asOf)) {
                            yield true;
                        }
                    }
                    yield rules.isEmpty();
                }
                case "not" -> !allOf(rules, m, asOf);
                default -> allOf(rules, m, asOf);
            };
        }
        String field = node.path("field").asString(null);
        if (field == null) {
            return false;
        }
        return compare(node.path("cmp").asString("eq"), resolve(field, m, asOf), node.get("value"));
    }

    private static boolean allOf(JsonNode rules, SegmentFacts m, Instant asOf) {
        for (JsonNode r : rules) {
            if (!eval(r, m, asOf)) {
                return false;
            }
        }
        return true;
    }

    // ---------- risoluzione dei campi ----------

    private static Object resolve(String field, SegmentFacts m, Instant asOf) {
        String f = strip(field);
        switch (f) {
            case "tier":
                return orAbsent(m.tier());
            case "status":
                return orAbsent(m.status());
            case "labels":
                return new ArrayList<Object>(m.labels() == null ? List.of() : m.labels());
            case "city":
                return m.city() == null || m.city().isBlank() ? ABSENT : m.city();
            case "age":
                return m.birthDate() == null ? ABSENT
                        : (double) Period.between(m.birthDate(), LocalDate.ofInstant(asOf, ROME)).getYears();
            case "registeredDaysAgo":
                return m.registeredAt() == null ? ABSENT : (double) ChronoUnit.DAYS.between(m.registeredAt(), asOf);
            case "balance.PTS":
                return (double) m.balancePts();
            case "lifetimeEarned.PTS":
                return (double) m.lifetimeEarnedPts();
            case "lastActivityDaysAgo":
                return m.lastActivityAt() == null ? ABSENT : (double) ChronoUnit.DAYS.between(m.lastActivityAt(), asOf);
            case "purchases.amount90d":
                return m.purchasesAmount90d();
            default:
                break;
        }
        if (f.startsWith("attributes.")) {
            JsonNode attrs = m.attributes();
            return attrs == null ? ABSENT : jsonToObject(attrs.get(f.substring("attributes.".length())));
        }
        String[] action = actionField(f);
        if (action != null) {
            SegmentFacts.ActionWindow w = m.actions() == null ? null : m.actions().get(action[0]);
            if (w == null) {
                return 0.0; // nessuna azione di quel tipo: conteggio zero, non campo assente
            }
            return (double) ("total".equals(action[1]) ? w.total() : w.count30d());
        }
        return ABSENT;
    }

    /** {@code actions.purchase.completed.count30d} → {@code [purchase.completed, count30d]}; altrimenti {@code null}. */
    private static String[] actionField(String f) {
        if (!f.startsWith("actions.")) {
            return null;
        }
        String rest = f.substring("actions.".length());
        for (String suffix : new String[]{".count30d", ".total"}) {
            if (rest.endsWith(suffix) && rest.length() > suffix.length()) {
                return new String[]{rest.substring(0, rest.length() - suffix.length()), suffix.substring(1)};
            }
        }
        return null;
    }

    private static String strip(String field) {
        return field.startsWith("member.") ? field.substring("member.".length()) : field;
    }

    private static Object orAbsent(String v) {
        return v == null ? ABSENT : v;
    }

    // ---------- comparatori (come il motore delle campagne, docs/03 §3.3) ----------

    private static boolean compare(String cmp, Object actual, JsonNode value) {
        if ("exists".equals(cmp)) {
            return actual != ABSENT && !(actual instanceof List<?> l && l.isEmpty());
        }
        if ("nexists".equals(cmp)) {
            return actual == ABSENT || (actual instanceof List<?> l && l.isEmpty());
        }
        if (actual == ABSENT) {
            return false;
        }
        boolean setOp = Set.of("in", "nin", "contains", "ncontains").contains(cmp);
        if (actual instanceof List<?> list && !setOp) {
            for (Object el : list) {
                if (scalar(cmp, el, value)) {
                    return true;
                }
            }
            return false;
        }
        if (actual instanceof List<?> list && ("in".equals(cmp) || "nin".equals(cmp))) {
            // lista ∩ valori non vuota (in) / vuota (nin)
            boolean any = false;
            for (Object el : list) {
                if ("nin".equals(cmp) && !(value != null && value.isArray() && allComparable(el, value))) {
                    return false; // tipi incompatibili → falsa anche per la negazione (docs/03 §3.3)
                }
                any |= inList(el, value);
            }
            return "in".equals(cmp) == any;
        }
        return scalar(cmp, actual, value);
    }

    /**
     * Confronto su uno scalare, come {@code ConditionEvaluator} di campaign-service e {@code AchievementRules} di
     * gamification-service (docs/03 §3.3): tipi incompatibili → falsa per ogni comparatore, negazioni comprese
     * ({@code neq}, {@code nin}, {@code ncontains}); {@code startsWith} solo su testo.
     */
    private static boolean scalar(String cmp, Object actual, JsonNode value) {
        return switch (cmp) {
            case "eq" -> equalsValue(actual, value);
            case "neq" -> comparable(actual, value) && !equalsValue(actual, value);
            case "gt" -> numeric(actual, value, c -> c > 0);
            case "gte" -> numeric(actual, value, c -> c >= 0);
            case "lt" -> numeric(actual, value, c -> c < 0);
            case "lte" -> numeric(actual, value, c -> c <= 0);
            case "in" -> inList(actual, value);
            case "nin" -> value != null && value.isArray() && allComparable(actual, value) && !inList(actual, value);
            case "contains" -> containsCompatible(actual, value) && contains(actual, value);
            case "ncontains" -> containsCompatible(actual, value) && !contains(actual, value);
            case "between" -> between(actual, value);
            case "startsWith" -> actual instanceof String s && value != null && value.isString()
                    && s.startsWith(value.asString(""));
            default -> false;
        };
    }

    /**
     * Tipi confrontabili per {@code eq}/{@code neq}/{@code in}/{@code nin}: numero con numero, booleano con booleano,
     * testo con testo.
     */
    // SPEC-GAP: Q-215 — testo numerico contro numero: qui incompatibile (falsa), l'opzione conservativa proposta in
    // Q-215/Q-216; campaign-service e gamification-service oggi lo confrontano come numero.
    private static boolean comparable(Object actual, JsonNode value) {
        if (actual == null || actual == ABSENT || value == null || value.isNull()) {
            return false;
        }
        if (value.isNumber()) {
            return actual instanceof Number;
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
            return ((Number) actual).doubleValue() == value.asDouble();
        }
        if (value.isBoolean()) {
            return (Boolean) actual == value.asBoolean();
        }
        return actual.equals(value.asString(""));
    }

    /** {@code contains}/{@code ncontains}: su lista (appartenenza) o su testo con un valore testuale (sottostringa). */
    private static boolean containsCompatible(Object actual, JsonNode value) {
        if (value == null || value.isNull()) {
            return false;
        }
        return actual instanceof List<?> || (actual instanceof String && value.isString());
    }

    private static boolean numeric(Object actual, JsonNode value, java.util.function.IntPredicate cmp) {
        if (!(actual instanceof Number a) || value == null || !value.isNumber()) {
            return false;
        }
        return cmp.test(Double.compare(a.doubleValue(), value.asDouble()));
    }

    private static boolean inList(Object actual, JsonNode value) {
        if (value == null || !value.isArray()) {
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
        return actual instanceof String s && value != null && value.isString() && s.contains(value.asString(""));
    }

    private static boolean between(Object actual, JsonNode value) {
        if (!(actual instanceof Number a) || value == null || !value.isArray() || value.size() < 2
                || !value.get(0).isNumber() || !value.get(1).isNumber()) {
            return false;
        }
        return a.doubleValue() >= value.get(0).asDouble() && a.doubleValue() <= value.get(1).asDouble();
    }

    private static Object jsonToObject(JsonNode n) {
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
            n.forEach(e -> list.add(jsonToObject(e)));
            return list;
        }
        return n.asString("");
    }
}
