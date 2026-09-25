package io.loyaltyhub.member.domain;

import io.loyaltyhub.common.condition.ConditionRules;
import io.loyaltyhub.common.condition.TypedCast;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    public static final Set<String> COMPARATORS = Set.copyOf(TypedCast.COMPARATORS);

    /** Campi semplici ammessi (senza prefisso); più {@code attributes.<k>} e {@code actions.<type>.count30d|total}. */
    public static final List<String> FIELDS = List.of(
            "tier", "status", "labels", "city", "age", "registeredDaysAgo", "balance.PTS", "lifetimeEarned.PTS",
            "lastActivityDaysAgo", "purchases.amount90d");

    /**
     * Problema di validazione di un criterio: percorso nel JSON (es. {@code rules[1].cmp}) e messaggio in italiano.
     * {@code typeMismatch}: forma corretta ma valore non convertibile nel tipo dichiarato del campo (Q-215).
     */
    public record Issue(String field, String message, boolean typeMismatch) {
        public Issue(String field, String message) {
            this(field, message, false);
        }
    }

    private SegmentCriteria() {
    }

    // ---------- validazione ----------

    /** Verifica la forma dei criteri senza tipi degli attributi custom; lista vuota = validi. */
    public static List<Issue> validate(JsonNode criteria) {
        return validate(criteria, Map.of());
    }

    /**
     * Verifica la forma dei criteri; lista vuota = validi. Criteri assenti o vuoti non sono ammessi per un DYNAMIC.
     * Regole comuni di lh-common ({@link ConditionRules}: gruppi, comparatori, forma del valore, Q-219/Q-222/Q-223/Q-224)
     * più i campi dello spazio esteso e il loro tipo: numeri per i contatori e i saldi, testo per {@code tier},
     * {@code status}, {@code city}, tipo della definizione per {@code attributes.<k>} ({@code attributeTypes}: chiave →
     * {@code STRING|NUMBER|BOOLEAN|DATE}). Un valore non convertibile nel tipo del campo ({@link TypedCast}) è un
     * problema {@code typeMismatch} (Q-215).
     */
    public static List<Issue> validate(JsonNode criteria, Map<String, String> attributeTypes) {
        List<Issue> issues = new ArrayList<>();
        if (criteria == null || criteria.isNull() || criteria.isMissingNode() || criteria.isEmpty()) {
            issues.add(new Issue("criteria", "servono criteri per un segmento dinamico"));
            return issues;
        }
        Map<String, String> types = attributeTypes == null ? Map.of() : attributeTypes;
        ConditionRules.Schema schema = new ConditionRules.Schema() {
            @Override
            public String fieldProblem(String field) {
                return knownField(field) ? null : "campo non disponibile per i segmenti: " + field;
            }

            @Override
            public TypedCast.Type declaredType(String field) {
                return fieldType(field, types);
            }

            @Override
            public boolean isList(String field) {
                return "labels".equals(strip(field));
            }

            @Override
            public boolean allowEmptyGroup(String op) {
                return false; // un gruppo vuoto non seleziona nulla di sensato (Q-307)
            }
        };
        for (ConditionRules.Issue i : ConditionRules.validate(criteria, "criteria", schema)) {
            issues.add(new Issue(i.path(), i.message(), i.typeMismatch()));
        }
        return issues;
    }

    /** Tipo dichiarato di un campo dello spazio esteso, {@code null} se non noto (attributo senza definizione). */
    static TypedCast.Type fieldType(String field, Map<String, String> attributeTypes) {
        String f = strip(field);
        switch (f) {
            case "tier", "status", "city":
                return TypedCast.Type.STRING;
            case "age", "registeredDaysAgo", "balance.PTS", "lifetimeEarned.PTS", "lastActivityDaysAgo",
                 "purchases.amount90d":
                return TypedCast.Type.NUMBER;
            default:
                break;
        }
        if (actionField(f) != null) {
            return TypedCast.Type.NUMBER;
        }
        if (f.startsWith("attributes.")) {
            String t = attributeTypes.get(f.substring("attributes.".length()));
            if (t == null) {
                return null;
            }
            return switch (t) {
                case "NUMBER" -> TypedCast.Type.NUMBER;
                case "BOOLEAN" -> TypedCast.Type.BOOLEAN;
                case "DATE" -> TypedCast.Type.DATE;
                case "STRING" -> TypedCast.Type.STRING;
                default -> null;
            };
        }
        return null;
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

    /**
     * Gruppo o foglia. Operatore di gruppo sconosciuto o mancante → falso (Q-223); {@code any} senza regole → falso
     * (Q-222); foglia senza {@code field} o {@code cmp} → falsa (Q-224, Q-219). La validazione li rifiuta prima.
     */
    private static boolean eval(JsonNode node, SegmentFacts m, Instant asOf) {
        if (node == null || !node.isObject()) {
            return false;
        }
        if (ConditionRules.isGroup(node)) {
            JsonNode rules = node.path("rules");
            String op = node.path("op").isString() ? node.path("op").asString() : "";
            return switch (op) {
                case "all" -> allOf(rules, m, asOf);
                case "any" -> {
                    for (JsonNode r : rules) {
                        if (eval(r, m, asOf)) {
                            yield true;
                        }
                    }
                    yield false;
                }
                case "not" -> !allOf(rules, m, asOf);
                default -> false;
            };
        }
        String field = node.path("field").isString() ? node.path("field").asString() : null;
        String cmp = node.path("cmp").isString() ? node.path("cmp").asString() : null;
        if (field == null || field.isBlank() || cmp == null) {
            return false;
        }
        return compare(cmp, resolve(field, m, asOf), node.get("value"));
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

    /**
     * Campo assente o {@code null} → falsa (tranne {@code nexists}); lista vuota = campo assente (Q-307). Su una lista
     * ({@code labels}) i comparatori scalari sono veri se almeno un elemento soddisfa; {@code contains/ncontains} per
     * appartenenza, {@code in} = intersezione non vuota, {@code nin} = intersezione vuota. Lo scalare usa il cast
     * tipizzato comune di lh-common ({@link TypedCast}, Q-215/Q-216 decise), identico a campaign, gamification ed
     * engagement: il valore della regola è convertito nel tipo del dato; conversione fallita → falsa per ogni
     * comparatore, negazioni comprese.
     */
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
        if (actual instanceof List<?> list) {
            return switch (cmp) {
                case "contains" -> TypedCast.listContains(list, value);
                case "ncontains" -> TypedCast.listNotContains(list, value);
                case "in" -> list.stream().anyMatch(el -> TypedCast.compare("in", el, value));
                // lista ∩ valori vuota; tipi incompatibili → falsa anche per la negazione (docs/03 §3.3)
                case "nin" -> list.stream().allMatch(el -> TypedCast.compare("nin", el, value));
                default -> list.stream().anyMatch(el -> TypedCast.compare(cmp, el, value));
            };
        }
        return TypedCast.compare(cmp, actual, value);
    }

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
