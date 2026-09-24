package io.loyaltyhub.engagement;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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
        registry.add("spring.datasource.url", () -> base + "&currentSchema=engagement");
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
        JsonNode snapshot = client().get().uri("/v1/portal/theme").retrieve().body(JsonNode.class);

        // 2. Mutazione dello stato
        long version = snapshot.path("version").asLong(0);



        JsonNode adminTheme = send("GET", "/v1/theme", "MARKETING:luca", null, 200);
        long currentVersion = adminTheme.path("version").asLong(0);

        Map<String, Object> colors = new HashMap<>(Map.of("primary", "#1FB98F", "secondary", "#7A5CFA", "coin", "#FFB547",
                "night", "#0E1B2C", "bg", "#F3F7F9"));
        Map<String, Object> body = new HashMap<>(Map.of("programName", "Club Aurora", "colors", colors,
                "heroTitle", "Ogni gesto conta", "version", currentVersion));

        send("PUT", "/v1/theme", "MARKETING:luca", body, 200);

        JsonNode mutated = client().get().uri("/v1/portal/theme").retrieve().body(JsonNode.class);
        assertThat(mutated.path("colors").path("primary").asString()).isEqualTo("#1FB98F");

        // 3. Reset demo
        send("POST", "/v1/demo/reset", "ADMIN:marta", null, 200);

        // 4. Verifica ripristino stato
        JsonNode restored = client().get().uri("/v1/portal/theme").retrieve().body(JsonNode.class);
        assertThat(restored.path("colors").path("primary").asString()).isEqualTo(snapshot.path("colors").path("primary").asString());

        // 5. Verifica idempotenza del reset
        send("POST", "/v1/demo/reset", "ADMIN:marta", null, 200);
        JsonNode restored2 = client().get().uri("/v1/portal/theme").retrieve().body(JsonNode.class);
        assertThat(restored2.path("colors").path("primary").asString()).isEqualTo(snapshot.path("colors").path("primary").asString());
    }

    private JsonNode send(String method, String path, String actor, Object body, int expected) {
        var spec = client().method(HttpMethod.valueOf(method)).uri(path).header("X-LH-Actor", actor);
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
