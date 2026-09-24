package io.loyaltyhub.reward;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoResetIT {

    private static final EmbeddedPostgres PG = startPg();

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=reward");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    void testDemoResetRestoresState() {
        // 1. Snapshot iniziale
        JsonNode snapshot = client().get().uri("/v1/approvals").header("X-LH-Actor", "LEGAL:elena").retrieve().body(JsonNode.class);

        // 2. Mutazione dello stato
        JsonNode created = send("POST", "/v1/rewards", "MARKETING:giulia", Map.of(
                "code", "RWD-IT-MUG", "name", "Tazza Aurora", "type", "PHYSICAL", "category", "CASA", "band", "F1",
                "fulfilment", "MANUAL", "stockTotal", 10), 201);
        String id = created.path("id").asString();

        send("POST", "/v1/rewards/" + id + "/transitions", "MARKETING:giulia", Map.of("action", "SUBMIT"), 200);

        JsonNode mutated = client().get().uri("/v1/approvals").header("X-LH-Actor", "LEGAL:elena").retrieve().body(JsonNode.class);
        assertThat(mutated.size()).isGreaterThan(snapshot.size());
        assertThat(mutated.toString()).contains("RWD-IT-MUG");

        // 3. Reset demo
        send("POST", "/v1/demo/reset", "ADMIN:marta", null, 200);

        // 4. Verifica ripristino stato
        JsonNode restored = client().get().uri("/v1/approvals").header("X-LH-Actor", "LEGAL:elena").retrieve().body(JsonNode.class);
        assertThat(restored.size()).isEqualTo(snapshot.size());
        assertThat(restored.toString()).doesNotContain("RWD-IT-MUG");

        // 5. Verifica idempotenza del reset
        send("POST", "/v1/demo/reset", "ADMIN:marta", null, 200);
        JsonNode restored2 = client().get().uri("/v1/approvals").header("X-LH-Actor", "LEGAL:elena").retrieve().body(JsonNode.class);
        assertThat(restored2.size()).isEqualTo(snapshot.size());
    }

    private JsonNode send(String method, String path, String actor, Object body, int expected) {
        var spec = client().method(org.springframework.http.HttpMethod.valueOf(method)).uri(path).header("X-LH-Actor", actor);
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return spec.exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(res.getStatusCode().value()).as(method + " " + path).isEqualTo(expected);
            return text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text);
        });
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
