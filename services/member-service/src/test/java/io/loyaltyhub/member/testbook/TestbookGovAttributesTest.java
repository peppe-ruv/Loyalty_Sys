package io.loyaltyhub.member.testbook;

import io.loyaltyhub.member.domain.AttributeDefinition;
import io.loyaltyhub.member.domain.MemberAttributes;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-GOV §10.1–§10.2 — attributi personalizzati (docs/03 §2 «mappa chiave → valore (string, number, boolean, date)»,
 * F-MBR-03, member §2): valore × tipo della definizione (tabella completa 4 × 16) e validazione dell'elenco delle
 * definizioni, logica pura di {@link MemberAttributes}. Via API gli stessi esiti sono 422 {@code MEMBER_INVALID} e
 * {@code ATTRIBUTE_DEFINITION_INVALID} (TestbookGovMemberIT, area ATU).
 */
class TestbookGovAttributesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gov/attribute-values.csv", numLinesToSkip = 1)
    void value(String id, String description, String type, String options, String value, String expected) {
        // Righe AMBIGUO (testo vuoto, 201 caratteri, data con ora, null che rimuove, chiave interna) — TESTBOOK: ambiguo,
        // vedi TB-GOV §13
        String key = switch (type) {
            case "UNDEFINED" -> "tUndefined";
            case "INTERNAL" -> "story";
            default -> "tAttr";
        };
        List<String> opts = options == null || options.isBlank() ? List.of() : Arrays.asList(options.split("\\|"));
        Map<String, AttributeDefinition> defs = Map.of("tAttr",
                new AttributeDefinition("tAttr", "Attributo", "NOT_OBJECT".equals(type) || "UNDEFINED".equals(type)
                        || "INTERNAL".equals(type) ? "STRING" : type, opts));
        JsonNode v = MAPPER.readTree(literal(value));
        JsonNode patch;
        if ("NOT_OBJECT".equals(type)) {
            patch = v;
        } else {
            ObjectNode o = MAPPER.createObjectNode();
            o.set(key, v);
            patch = o;
        }
        ObjectNode current = MAPPER.createObjectNode();
        current.put("tAttr", "precedente");
        List<MemberAttributes.Problem> problems = new ArrayList<>();
        ObjectNode merged = MemberAttributes.merge(current, patch, defs, problems);
        if ("OK".equals(expected)) {
            assertThat(problems).as("%s: %s", id, description).isEmpty();
            if (v.isNull()) {
                assertThat(merged.has("tAttr")).as("%s: null rimuove la chiave", id).isFalse();
            } else {
                assertThat(merged.get("tAttr")).as("%s: valore salvato", id).isEqualTo(v);
            }
        } else {
            assertThat(problems).as("%s: %s", id, description).isNotEmpty();
            assertThat(merged).as("%s: nessuna modifica parziale", id).isNull();
        }
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gov/attribute-definitions.csv", numLinesToSkip = 1)
    void definition(String id, String description, String key, String label, String type, String options,
                    String expected) {
        // Righe AMBIGUO (formato della chiave, etichetta, opzioni, numero massimo) — TESTBOOK: ambiguo, vedi TB-GOV §13
        List<AttributeDefinition> defs = new ArrayList<>();
        List<String> opts = options == null || options.isBlank() ? null
                : Arrays.stream(options.split("\\|")).map(o -> "EMPTY".equals(o) ? "" : o).toList();
        String lbl = switch (label) {
            case "NULL" -> null;
            case "EMPTY" -> "";
            case "SPACES" -> "   ";
            case "L60" -> "e".repeat(60);
            case "L61" -> "e".repeat(61);
            default -> label;
        };
        String ty = "NULL".equals(type) ? null : type;
        switch (key) {
            case "NULL" -> defs.add(new AttributeDefinition(null, lbl, ty, opts));
            case "DUP" -> {
                defs.add(new AttributeDefinition("tDup", lbl, ty, opts));
                defs.add(new AttributeDefinition("tDup", lbl, ty, opts));
            }
            case "N30", "N31" -> {
                int count = "N30".equals(key) ? 30 : 31;
                for (int i = 0; i < count; i++) {
                    defs.add(new AttributeDefinition("tKey" + i, lbl, ty, opts));
                }
            }
            default -> defs.add(new AttributeDefinition(key, lbl, ty, opts));
        }
        List<MemberAttributes.Problem> problems;
        try {
            problems = MemberAttributes.validateDefinitions(defs);
        } catch (RuntimeException e) {
            // Una definizione non valida deve diventare un problema sul campo (422), non un errore interno (500).
            throw new AssertionError(id + ": " + description + " — eccezione " + e + " invece di un problema su "
                    + expected, e);
        }
        if ("OK".equals(expected)) {
            assertThat(problems).as("%s: %s", id, description).isEmpty();
        } else {
            assertThat(problems).as("%s: %s", id, description).extracting(MemberAttributes.Problem::field)
                    .anyMatch(f -> f.endsWith(expected));
        }
    }

    /** Valori dei CSV: JSON con apici singoli; {@code S200}/{@code S201} = testo di 200/201 caratteri. */
    private static String literal(String raw) {
        return switch (raw) {
            case "S200" -> "\"" + "a".repeat(200) + "\"";
            case "S201" -> "\"" + "a".repeat(201) + "\"";
            default -> raw.replace('\'', '"');
        };
    }
}
