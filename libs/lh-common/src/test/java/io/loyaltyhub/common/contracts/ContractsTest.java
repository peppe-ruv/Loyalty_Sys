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

            // Coerenza del dataschema dichiarato (docs/05 §2).
            String expectedDataschema = "urn:loyaltyhub:schema:" + familyDotName + ":1";
            if (!expectedDataschema.equals(event.path("dataschema").asText())) {
                failures.add(name + " ⟶ dataschema atteso " + expectedDataschema
                        + " ma trovato " + event.path("dataschema").asText());
            }

            JsonSchema dataSchema = schema("classpath:contracts/events/" + family + "/" + eventName + ".schema.json");
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
