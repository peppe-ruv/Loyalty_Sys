package io.loyaltyhub.member.domain;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Regole degli attributi personalizzati (F-MBR-03, docs/03 §2: "mappa chiave → valore (string, number, boolean,
 * date)") e delle etichette libere. Puro: nessun accesso a DB.
 * <ul>
 *   <li>Un valore è ammesso solo per una chiave definita e del tipo della definizione; {@code null} lo rimuove.</li>
 *   <li>Le chiavi interne della demo ({@code story}, la storia della persona) non sono attributi: restano nel JSON ma
 *       non si vedono, non si modificano e non viaggiano nei fatti.</li>
 *   <li>Etichette: minuscole, {@code [a-z0-9-_]}, al più 30 caratteri, senza doppioni (ordine conservato).</li>
 * </ul>
 */
public final class MemberAttributes {

    public record Problem(String field, String message) {
    }

    public static final Set<String> INTERNAL_KEYS = Set.of("story");
    static final Pattern KEY = Pattern.compile("^[a-z][a-zA-Z0-9]{1,39}$");
    static final Pattern LABEL = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,29}$");

    private MemberAttributes() {
    }

    private static final tools.jackson.databind.ObjectMapper MAPPER = new tools.jackson.databind.ObjectMapper();

    /** JSON grezzo della colonna {@code attributes} ({@code null} o non valido ⇒ oggetto vuoto). */
    public static JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return JsonNodeFactory.instance.objectNode();
        }
        try {
            return MAPPER.readTree(json);
        } catch (RuntimeException e) {
            return JsonNodeFactory.instance.objectNode();
        }
    }

    /** Solo gli attributi personalizzati (senza chiavi interne). */
    public static ObjectNode visible(JsonNode all) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        if (all != null && all.isObject()) {
            for (var e : all.properties()) {
                if (!INTERNAL_KEYS.contains(e.getKey())) {
                    out.set(e.getKey(), e.getValue());
                }
            }
        }
        return out;
    }

    /**
     * Applica {@code patch} (chiave → valore, {@code null} rimuove) a {@code current} validando contro le definizioni.
     * Ritorna il nuovo JSON completo (chiavi interne conservate) oppure, se {@code problems} non è vuoto, {@code null}.
     */
    public static ObjectNode merge(JsonNode current, JsonNode patch, Map<String, AttributeDefinition> defs,
                                   List<Problem> problems) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        if (current != null && current.isObject()) {
            out.setAll((ObjectNode) current);
        }
        if (patch == null || patch.isNull()) {
            return out;
        }
        if (!patch.isObject()) {
            problems.add(new Problem("attributes", "oggetto chiave → valore"));
            return null;
        }
        for (var e : patch.properties()) {
            String key = e.getKey();
            JsonNode value = e.getValue();
            String field = "attributes." + key;
            AttributeDefinition def = defs.get(key);
            if (def == null || INTERNAL_KEYS.contains(key)) {
                problems.add(new Problem(field, "attributo non definito"));
                continue;
            }
            if (value == null || value.isNull()) {
                out.remove(key);
                continue;
            }
            String error = check(def, value);
            if (error != null) {
                problems.add(new Problem(field, error));
            } else {
                out.set(key, value);
            }
        }
        return problems.isEmpty() ? out : null;
    }

    static String check(AttributeDefinition def, JsonNode value) {
        return switch (def.type()) {
            case "NUMBER" -> value.isNumber() ? null : "deve essere un numero";
            case "BOOLEAN" -> value.isBoolean() ? null : "deve essere vero o falso";
            case "DATE" -> {
                if (!value.isString()) {
                    yield "deve essere una data AAAA-MM-GG";
                }
                try {
                    LocalDate.parse(value.asString());
                    yield null;
                } catch (DateTimeParseException ex) {
                    yield "deve essere una data AAAA-MM-GG";
                }
            }
            default -> {
                if (!value.isString() || value.asString().isBlank()) {
                    yield "deve essere un testo";
                }
                if (!def.options().isEmpty() && !def.options().contains(value.asString())) {
                    yield "uno tra " + String.join(", ", def.options());
                }
                yield value.asString().length() > 200 ? "al massimo 200 caratteri" : null;
            }
        };
    }

    /** Etichette normalizzate (minuscolo, senza spazi ai bordi, senza doppioni) oppure problemi. */
    public static List<String> labels(List<String> raw, List<Problem> problems) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String l : raw) {
            String v = l == null ? "" : l.trim().toLowerCase(java.util.Locale.ROOT);
            if (!LABEL.matcher(v).matches()) {
                problems.add(new Problem("labels", "etichetta non valida: \"" + l + "\" (minuscole, cifre, - e _)"));
            } else if (seen.add(v)) {
                out.add(v);
            }
        }
        if (out.size() > 20) {
            problems.add(new Problem("labels", "al massimo 20 etichette"));
        }
        return out;
    }

    /** Validazione dell'elenco delle definizioni (PUT completo da BO-03). */
    public static List<Problem> validateDefinitions(List<AttributeDefinition> defs) {
        List<Problem> problems = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < defs.size(); i++) {
            AttributeDefinition d = defs.get(i);
            String at = "[" + i + "]";
            if (d.key() == null || !KEY.matcher(d.key()).matches() || INTERNAL_KEYS.contains(d.key())) {
                problems.add(new Problem(at + ".key", "camelCase, lettera iniziale minuscola, 2–40 caratteri"));
            } else if (!keys.add(d.key())) {
                problems.add(new Problem(at + ".key", "chiave duplicata: " + d.key()));
            }
            if (d.label() == null || d.label().isBlank() || d.label().length() > 60) {
                problems.add(new Problem(at + ".label", "obbligatoria, al massimo 60 caratteri"));
            }
            // Tipo assente → problema di validazione (422), non NullPointerException (List.of rifiuta contains(null)).
            if (d.type() == null || !AttributeDefinition.TYPES.contains(d.type())) {
                problems.add(new Problem(at + ".type", "uno tra STRING, NUMBER, BOOLEAN, DATE"));
            } else if (!d.options().isEmpty() && !"STRING".equals(d.type())) {
                problems.add(new Problem(at + ".options", "opzioni solo per il tipo STRING"));
            }
            if (d.options().stream().anyMatch(o -> o == null || o.isBlank())
                    || new HashSet<>(d.options()).size() != d.options().size()) {
                problems.add(new Problem(at + ".options", "opzioni non vuote e senza doppioni"));
            }
        }
        if (defs.size() > 30) {
            problems.add(new Problem("definitions", "al massimo 30 attributi"));
        }
        return problems;
    }
}
