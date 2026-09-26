package io.loyaltyhub.common.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test di contratto (docs/05 §9): ogni esempio valida contro lo schema dell'envelope e contro lo schema
 * del proprio {@code type}; ogni schema di {@code data} ha almeno un esempio. Niente Docker: solo file.
 */
class ContractsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonSchemaFactory FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final PathMatchingResourcePatternResolver RESOLVER =
            new PathMatchingResourcePatternResolver();

    @Test
    void everyExampleValidatesAgainstEnvelopeAndDataSchema() throws Exception {
        JsonSchema envelope = schema("classpath:contracts/events/envelope.schema.json");
        Resource[] examples = RESOLVER.getResources("classpath*:contracts/events/examples/*.json");
        assertThat(examples).as("esempi presenti").isNotEmpty();

        List<String> failures = new ArrayList<>();
        for (Resource example : examples) {
            String name = example.getFilename();
            JsonNode event = read(example);

            Set<ValidationMessage> envelopeErrors = envelope.validate(event);
            if (!envelopeErrors.isEmpty()) {
                failures.add(name + " ⟶ envelope: " + envelopeErrors);
            }

            String type = event.path("type").asText();
            String familyDotName = type.replaceFirst("^io\\.loyaltyhub\\.", "");
            int firstDot = familyDotName.indexOf('.');
            String family = familyDotName.substring(0, firstDot);
            String eventName = familyDotName.substring(firstDot + 1);

            // Coerenza del dataschema dichiarato (docs/05 §2): <famiglia>.<nome>:<n>, la versione n>1 in <nome>.v<n>.
            String prefix = "urn:loyaltyhub:schema:" + familyDotName + ":";
            String dataschema = event.path("dataschema").asText();
            int version = dataschema.startsWith(prefix) ? parseVersion(dataschema.substring(prefix.length())) : -1;
            if (version < 1) {
                failures.add(name + " ⟶ dataschema atteso " + prefix + "<versione> ma trovato " + dataschema);
                continue;
            }

            JsonSchema dataSchema = schema("classpath:contracts/events/" + family + "/" + schemaFile(eventName, version));
            Set<ValidationMessage> dataErrors = dataSchema.validate(event.path("data"));
            if (!dataErrors.isEmpty()) {
                failures.add(name + " ⟶ data: " + dataErrors);
            }

            // Gli audit hanno l'attore obbligatorio (docs/05 §6).
            if (family.equals("audit") && event.path("lhactor").asText("").isBlank()) {
                failures.add(name + " ⟶ audit senza lhactor");
            }
        }
        assertThat(failures).as("esempi non conformi").isEmpty();
    }

    @Test
    void everyDataSchemaHasAnExample() throws Exception {
        Set<String> exampleNames = new TreeSet<>();
        for (Resource ex : RESOLVER.getResources("classpath*:contracts/events/examples/*.json")) {
            exampleNames.add(ex.getFilename());
        }

        List<String> orphans = new ArrayList<>();
        for (Resource schema : RESOLVER.getResources("classpath*:contracts/events/**/*.schema.json")) {
            String file = schema.getFilename();
            if (file.equals("envelope.schema.json")) {
                continue;
            }
            // family dal segmento di cartella, name dal file
            String uri = schema.getURI().toString();
            String family = uri.replaceAll(".*/contracts/events/([^/]+)/[^/]+$", "$1");
            String eventName = file.replace(".schema.json", "");
            String expectedExample = family + "." + eventName + ".json";
            if (!exampleNames.contains(expectedExample)) {
                orphans.add(expectedExample + " (schema senza esempio)");
            }
        }
        assertThat(orphans).as("schemi senza esempio").isEmpty();
    }

    /**
     * Dati personali fuori dal bus (ADR-032, CLAUDE.md regola 10): ogni campo di ogni schema dichiara {@code x-lh-pii};
     * un campo {@code x-lh-pii: true} è ammesso solo in una versione superata da una versione più alta esistente
     * ({@code x-lh-superseded-by}), che resta finché dura la doppia lettura (Q-346).
     */
    @Test
    void personalDataOnlyInSupersededVersions() throws Exception {
        List<String> failures = new ArrayList<>();
        for (Resource schema : RESOLVER.getResources("classpath*:contracts/events/**/*.schema.json")) {
            String file = schema.getFilename();
            if (file.equals("envelope.schema.json")) {
                continue;
            }
            String family = schema.getURI().toString().replaceAll(".*/contracts/events/([^/]+)/[^/]+$", "$1");
            String rel = family + "/" + file;
            JsonNode node = read(schema);
            List<String> missing = new ArrayList<>();
            List<String> pii = new ArrayList<>();
            piiFields(node, "", missing, pii);
            missing.forEach(field -> failures.add(rel + " ⟶ il campo " + field + " non dichiara x-lh-pii"));

            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*?)(?:\\.v(\\d+))?\\.schema\\.json$").matcher(file);
            m.matches();
            int version = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
            if (!node.path("$id").asText().endsWith(":" + version)) {
                failures.add(rel + " ⟶ $id " + node.path("$id").asText() + " non termina con :" + version);
            }
            if (pii.isEmpty()) {
                continue;
            }
            String supersededBy = node.path("x-lh-superseded-by").asText("");
            int next = supersededBy.matches(".*:\\d+$") ? parseVersion(supersededBy.substring(supersededBy.lastIndexOf(':') + 1)) : -1;
            boolean nextExists = next > version && RESOLVER.getResource(
                    "classpath:contracts/events/" + family + "/" + schemaFile(m.group(1), next)).exists();
            if (!nextExists) {
                failures.add(rel + " ⟶ campi x-lh-pii: true " + pii + " in una versione non superata");
            }
        }
        assertThat(failures).as("dati personali negli schemi degli eventi").isEmpty();
    }

    private static void piiFields(JsonNode node, String path, List<String> missing, List<String> pii) {
        node.path("properties").properties().forEach(entry -> {
            String at = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
            JsonNode flag = entry.getValue().get("x-lh-pii");
            if (flag == null || !flag.isBoolean()) {
                missing.add(at);
            } else if (flag.booleanValue()) {
                pii.add(at);
            }
            piiFields(entry.getValue(), at, missing, pii);
            if (entry.getValue().path("items").isObject()) {
                piiFields(entry.getValue().path("items"), at + "[]", missing, pii);
            }
        });
    }

    private static String schemaFile(String eventName, int version) {
        return version > 1 ? eventName + ".v" + version + ".schema.json" : eventName + ".schema.json";
    }

    private static int parseVersion(String text) {
        return text.matches("[1-9][0-9]*") ? Integer.parseInt(text) : -1;
    }

    private JsonSchema schema(String location) throws Exception {
        try (InputStream in = RESOLVER.getResource(location).getInputStream()) {
            return FACTORY.getSchema(in);
        }
    }

    private JsonNode read(Resource resource) throws Exception {
        try (InputStream in = resource.getInputStream()) {
            return MAPPER.readTree(in);
        }
    }
}
