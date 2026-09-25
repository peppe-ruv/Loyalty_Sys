package io.loyaltyhub.common.condition;

import io.loyaltyhub.common.condition.TypedCast.Type;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validazione di salvataggio di un albero di condizioni (docs/03 §3.3), comune ai servizi che ne salvano uno (campagne,
 * segmenti, filtri degli obiettivi, regole di notifica). Decisioni conservative (Q-215, Q-219, Q-222, Q-223, Q-224,
 * Q-295, Q-179): ciò che il motore tratterebbe come «sempre falso» per forma è rifiutato qui, con il percorso JSON del
 * problema (es. {@code conditions.rules[1].cmp}).
 * <ul>
 *   <li>gruppo {@code {op, rules}}: {@code op} tra {@code all/any/not} (Q-223), {@code rules} elenco;
 *   {@code any} senza regole rifiutato (Q-222; gli altri gruppi vuoti secondo lo {@link Schema});</li>
 *   <li>foglia {@code {field, cmp, value}}: {@code field} presente (Q-224), {@code cmp} presente (Q-219) e tra i 14 di
 *   docs/03 §3.3 (Q-218);</li>
 *   <li>valore: presente tranne per {@code exists/nexists}; elenco per {@code in/nin}; due estremi per
 *   {@code between}; testo per {@code startsWith}; confronti d'ordine su numero, data o istante;</li>
 *   <li>tipo dichiarato del campo noto al servizio ({@link Schema#declaredType}): il valore deve convertirsi in quel tipo
 *   con {@link TypedCast} (Q-215), altrimenti la foglia sarebbe sempre falsa.</li>
 * </ul>
 */
public final class ConditionRules {

    public static final Set<String> GROUP_OPS = Set.of("all", "any", "not");

    /**
     * Problema di un nodo: percorso JSON e messaggio in italiano. {@code typeMismatch} = forma corretta ma valore non
     * convertibile nel tipo dichiarato del campo (422 {@code CONDITION_INVALID}).
     */
    public record Issue(String path, String message, boolean typeMismatch) {
    }

    /** Ciò che il servizio sa dei campi. Default: nessun vincolo sui campi, tipo non dichiarato. */
    public interface Schema {
        /** Messaggio se il campo non è ammesso dal servizio, {@code null} se va bene. */
        default String fieldProblem(String field) {
            return null;
        }

        /** Tipo dichiarato del campo, {@code null} se il servizio non lo conosce (nessuna verifica di tipo). */
        default Type declaredType(String field) {
            return null;
        }

        /** Campo elenco ({@code member.labels}, {@code member.segments}): {@code contains/ncontains} per appartenenza. */
        default boolean isList(String field) {
            return false;
        }

        /** Gruppo {@code all}/{@code not} senza regole ammesso ({@code any} vuoto non lo è mai, Q-222). */
        default boolean allowEmptyGroup(String op) {
            return !"any".equals(op);
        }
    }

    /** Nessun vincolo sui campi: solo forma e comparatori. */
    public static final Schema ANY_FIELD = new Schema() {
    };

    private ConditionRules() {
    }

    /**
     * Problemi dell'albero {@code root} (vuoto = valido). {@code null} e {@code {}} alla radice = nessuna condizione
     * (valido: sta al servizio decidere se le condizioni sono obbligatorie).
     */
    public static List<Issue> validate(JsonNode root, String rootPath, Schema schema) {
        List<Issue> out = new ArrayList<>();
        if (root == null || root.isNull() || root.isMissingNode() || (root.isObject() && root.isEmpty())) {
            return out;
        }
        check(root, rootPath, schema == null ? ANY_FIELD : schema, out);
        return out;
    }

    /** Vero se il nodo è un gruppo ({@code op} o {@code rules} presenti), altrimenti una foglia. */
    public static boolean isGroup(JsonNode node) {
        return node != null && node.isObject() && (node.has("op") || node.has("rules"));
    }

    private static void check(JsonNode node, String path, Schema schema, List<Issue> out) {
        if (node == null || !node.isObject()) {
            out.add(new Issue(path, "atteso un gruppo {op, rules} o una condizione {field, cmp, value}", false));
            return;
        }
        if (isGroup(node)) {
            JsonNode opNode = node.get("op");
            String op = opNode != null && opNode.isString() ? opNode.asString() : null;
            boolean knownOp = op != null && GROUP_OPS.contains(op);
            if (!knownOp) {
                out.add(new Issue(path + ".op", op == null
                        ? "operatore di gruppo mancante: usa all, any o not"
                        : "operatore di gruppo sconosciuto «" + op + "»: usa all, any o not", false));
            }
            JsonNode rules = node.get("rules");
            if (rules == null || !rules.isArray()) {
                out.add(new Issue(path + ".rules", "il gruppo richiede l'elenco rules", false));
                return;
            }
            if (knownOp && rules.isEmpty() && !schema.allowEmptyGroup(op)) {
                out.add(new Issue(path + ".rules", "il gruppo «" + op + "» deve contenere almeno una regola", false));
            }
            for (int i = 0; i < rules.size(); i++) {
                check(rules.get(i), path + ".rules[" + i + "]", schema, out);
            }
            return;
        }
        leaf(node, path, schema, out);
    }

    private static void leaf(JsonNode node, String path, Schema schema, List<Issue> out) {
        JsonNode f = node.get("field");
        String field = f != null && f.isString() ? f.asString().trim() : "";
        if (field.isEmpty()) {
            out.add(new Issue(path + ".field", "campo mancante", false));
        } else {
            String p = schema.fieldProblem(field);
            if (p != null) {
                out.add(new Issue(path + ".field", p, false));
            }
        }
        JsonNode c = node.get("cmp");
        if (c == null || c.isNull()) {
            out.add(new Issue(path + ".cmp", "comparatore mancante", false));
            return;
        }
        String cmp = c.isString() ? c.asString() : c.toString();
        if (!TypedCast.COMPARATORS.contains(cmp)) {
            out.add(new Issue(path + ".cmp", "comparatore sconosciuto «" + cmp + "»", false));
            return;
        }
        if ("exists".equals(cmp) || "nexists".equals(cmp)) {
            return;
        }
        String vp = path + ".value";
        JsonNode value = node.get("value");
        if (value == null || value.isNull() || value.isMissingNode()) {
            out.add(new Issue(vp, "valore mancante: la foglia su «" + field + "» richiede value", false));
            return;
        }
        Type declared = field.isEmpty() ? null : schema.declaredType(field);
        boolean list = !field.isEmpty() && schema.isList(field);
        switch (cmp) {
            case "in", "nin" -> {
                if (!value.isArray()) {
                    out.add(new Issue(vp, "per " + cmp + " serve un elenco di valori", false));
                    return;
                }
                for (int i = 0; i < value.size(); i++) {
                    element(value.get(i), vp + "[" + i + "]", declared, out);
                }
            }
            case "between" -> {
                if (!value.isArray() || value.size() != 2) {
                    out.add(new Issue(vp, "per between servono due valori [min, max]", false));
                    return;
                }
                for (int i = 0; i < 2; i++) {
                    ordered(value.get(i), vp + "[" + i + "]", cmp, declared, out);
                }
                Type t0 = orderedType(value.get(0), declared);
                Type t1 = orderedType(value.get(1), declared);
                if (declared == null && t0 != null && t1 != null && t0 != t1) {
                    out.add(new Issue(vp, "gli estremi di between devono avere lo stesso tipo", true));
                }
            }
            case "gt", "gte", "lt", "lte" -> ordered(value, vp, cmp, declared, out);
            case "startsWith" -> {
                if (!value.isString()) {
                    out.add(new Issue(vp, "per startsWith serve un testo", false));
                } else if (declared != null && declared != Type.STRING) {
                    out.add(new Issue(vp, "startsWith vale solo su un campo di testo (il campo è " + label(declared) + ")", true));
                }
            }
            case "contains", "ncontains" -> {
                if (!scalar(value)) {
                    out.add(new Issue(vp, "per " + cmp + " serve un valore singolo", false));
                } else if (list) {
                    // appartenenza a un elenco: il tipo degli elementi non è dichiarato
                } else if (declared != null && (declared != Type.STRING || !value.isString())) {
                    out.add(new Issue(vp, cmp + " vale solo su un testo o un elenco (il campo è " + label(declared) + ")", true));
                }
            }
            default -> element(value, vp, list ? null : declared, out); // eq, neq
        }
    }

    private static void element(JsonNode v, String path, Type declared, List<Issue> out) {
        if (!scalar(v)) {
            out.add(new Issue(path, "serve un valore singolo (numero, testo, booleano o data)", false));
        } else if (declared != null && !TypedCast.castsTo(v, declared)) {
            out.add(new Issue(path, "valore " + v + " non convertibile nel tipo del campo (" + label(declared) + ")", true));
        }
    }

    private static void ordered(JsonNode v, String path, String cmp, Type declared, List<Issue> out) {
        if (!scalar(v)) {
            out.add(new Issue(path, "per " + cmp + " serve un numero o una data", false));
            return;
        }
        if (declared != null) {
            if (declared != Type.NUMBER && declared != Type.DATE && declared != Type.INSTANT) {
                out.add(new Issue(path, cmp + " non vale su un campo " + label(declared), true));
            } else if (!TypedCast.castsTo(v, declared)) {
                out.add(new Issue(path, "valore " + v + " non convertibile nel tipo del campo (" + label(declared) + ")", true));
            }
            return;
        }
        if (orderedType(v, null) == null) {
            out.add(new Issue(path, "per " + cmp + " serve un numero o una data ISO (AAAA-MM-GG o data e ora con fuso)", false));
        }
    }

    /** Tipo ordinabile in cui il valore si converte (numero, data, istante), {@code null} se nessuno. */
    private static Type orderedType(JsonNode v, Type declared) {
        if (declared != null) {
            return TypedCast.castsTo(v, declared) ? declared : null;
        }
        for (Type t : new Type[]{Type.NUMBER, Type.DATE, Type.INSTANT}) {
            if (TypedCast.castsTo(v, t)) {
                return t;
            }
        }
        return null;
    }

    private static boolean scalar(JsonNode v) {
        return v != null && !v.isNull() && !v.isMissingNode() && !v.isArray() && !v.isObject();
    }

    private static String label(Type t) {
        return switch (t) {
            case NUMBER -> "numero";
            case STRING -> "testo";
            case BOOLEAN -> "booleano";
            case DATE -> "data";
            case INSTANT -> "data e ora";
        };
    }
}
