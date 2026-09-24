package io.loyaltyhub.member;

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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
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
        registry.add("spring.datasource.url", () -> base + "&currentSchema=member");
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
        JsonNode snapshot = client().get().uri("/v1/members?size=50").retrieve().body(JsonNode.class);
        JsonNode matteoSnapshot = client().get().uri("/v1/members/MBR-000010").retrieve().body(JsonNode.class);

        // 2. Mutazione dello stato
        send("PATCH", "/v1/members/MBR-000010", "CARE:paolo.care",
                Map.of("attributes", Map.of("householdSize", 4), "labels", List.of("VIP ", "vip", "newsletter")), 200);

        JsonNode mutated = client().get().uri("/v1/members/MBR-000010").retrieve().body(JsonNode.class);
        assertThat(mutated.path("attributes").path("householdSize").asInt()).isEqualTo(4);

        // 3. Reset demo
        send("POST", "/v1/demo/reset", "ADMIN:marta", null, 200);

        // 4. Verifica ripristino stato
        JsonNode restoredMatteo = client().get().uri("/v1/members/MBR-000010").retrieve().body(JsonNode.class);
        assertThat(restoredMatteo.path("attributes").toString()).isEqualTo(matteoSnapshot.path("attributes").toString());
        assertThat(restoredMatteo.path("labels").toString()).isEqualTo(matteoSnapshot.path("labels").toString());

        JsonNode restoredList = client().get().uri("/v1/members?size=50").retrieve().body(JsonNode.class);
        assertThat(restoredList.path("page").path("totalItems").asInt()).isEqualTo(snapshot.path("page").path("totalItems").asInt());

        // 5. Verifica idempotenza del reset
        send("POST", "/v1/demo/reset", "ADMIN:marta", null, 200);
        JsonNode restoredMatteo2 = client().get().uri("/v1/members/MBR-000010").retrieve().body(JsonNode.class);
        assertThat(restoredMatteo2.path("attributes").toString()).isEqualTo(matteoSnapshot.path("attributes").toString());
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
