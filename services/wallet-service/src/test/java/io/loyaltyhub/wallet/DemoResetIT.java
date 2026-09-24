package io.loyaltyhub.wallet;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
        registry.add("spring.datasource.url", () -> base + "&currentSchema=wallet");
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
        JsonNode liabilitySnapshot = client().get().uri("/v1/liability?currency=PTS").retrieve().body(JsonNode.class);
        long originalOutstanding = liabilitySnapshot.path("outstanding").asLong();

        // 2. Mutazione dello stato (concessione punti)
        String effectId = "EFF-DEMO-RESET-1";
        String memberId = "MBR-000007";
        Map<String, Object> data = Map.of(
                "effectId", effectId, "campaignCode", "CMP-PURCHASE-BASE", "actionId", "ACT-" + effectId,
                "actionType", "purchase.completed", "currency", "PTS", "baseAmount", 500,
                "campaignMultiplier", 1.0, "amount", 500, "tierMultiplierApplies", false,
                "pendingDays", 0);
        Map<String, Object> event = Map.of(
                "specversion", "1.0", "id", "EV-" + effectId, "source", "urn:loyaltyhub:service:campaign",
                "type", "io.loyaltyhub.effect.points.grant", "subject", "member:" + memberId,
                "time", Instant.now().toString(), "lhcorrelationid", "ACT-" + effectId, "lhhop", 0, "data", data);

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class))) {
            producer.send(new ProducerRecord<>("lh.effects.v1", memberId, mapper.writeValueAsString(event))).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Attesa asincrona
        long deadline = System.currentTimeMillis() + 15_000;
        long currentOutstanding = originalOutstanding;
        while (System.currentTimeMillis() < deadline) {
            JsonNode currentLiability = client().get().uri("/v1/liability?currency=PTS").retrieve().body(JsonNode.class);
            if (currentLiability != null) {
                currentOutstanding = currentLiability.path("outstanding").asLong();
                if (currentOutstanding > originalOutstanding) {
                    break;
                }
            }
            sleep();
        }
        assertThat(currentOutstanding).isGreaterThan(originalOutstanding);

        // 3. Reset demo
        send("POST", "/v1/demo/reset", "ADMIN:marta", null, 200);

        // 4. Verifica ripristino stato
        JsonNode restoredLiability = client().get().uri("/v1/liability?currency=PTS").retrieve().body(JsonNode.class);
        assertThat(restoredLiability.path("outstanding").asLong()).isEqualTo(originalOutstanding);

        // 5. Verifica idempotenza del reset
        send("POST", "/v1/demo/reset", "ADMIN:marta", null, 200);
        JsonNode restoredLiability2 = client().get().uri("/v1/liability?currency=PTS").retrieve().body(JsonNode.class);
        assertThat(restoredLiability2.path("outstanding").asLong()).isEqualTo(originalOutstanding);
    }

    private JsonNode send(String method, String path, String actor, Object body, int expected) {
        var spec = client().method(HttpMethod.valueOf(method)).uri(path).header("X-LH-Actor", actor);
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(res.getStatusCode().value()).as(method + " " + path).isEqualTo(expected);
            return text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text);
        });
    }

    private static void sleep() {
        try {
            Thread.sleep(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
