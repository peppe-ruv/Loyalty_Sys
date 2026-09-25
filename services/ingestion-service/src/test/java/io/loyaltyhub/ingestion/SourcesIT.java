package io.loyaltyhub.ingestion;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gestione delle fonti da BO-09 (F-ING-05, docs/servizi/ingestion-service.md §3 {@code PUT /v1/sources/{code}}):
 * solo ADMIN ({@code program.config}), fonte spenta → eventi {@code REJECTED/SOURCE_DISABLED}, tipi ammessi →
 * {@code TYPE_NOT_ALLOWED}, audit dell'aggiornamento, 404/422 sugli input.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.dlq.v1", "lh.audit.v1", "lh.facts.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SourcesIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String ADMIN = "ADMIN:marta.admin";

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=ingestion");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    void onlyAdminTogglesASourceAndDisabledSourceRejectsEvents() {
        Map<String, Object> off = Map.of("enabled", false);
        send("PUT", "/v1/sources/ecommerce", null, off, 403);
        send("PUT", "/v1/sources/ecommerce", "ANALYST:sara.analyst", off, 403);
        send("PUT", "/v1/sources/ecommerce", "MARKETING:luca.marketing", off, 403);
        send("PUT", "/v1/sources/ecommerce", "CARE:paolo.care", off, 403);
        assertThat(source("ecommerce").path("enabled").asBoolean()).isTrue();

        assertThat(send("POST", "/v1/events", null, purchase("SRC-ON-1"), 202).path("status").asString()).isEqualTo("ACCEPTED");

        try (KafkaConsumer<String, String> audit = consumer("sources-audit")) {
            audit.subscribe(List.of("lh.audit.v1"));
            JsonNode updated = send("PUT", "/v1/sources/ecommerce", ADMIN, off, 200);
            assertThat(updated.path("enabled").asBoolean()).isFalse();
            assertThat(updated.path("allowedTypes").toString()).contains("purchase.completed");
            ConsumerRecord<String, String> entry = poll(audit, r -> r.key().equals("source:ecommerce"));
            assertThat(entry).as("voce di audit della fonte").isNotNull();
            assertThat(entry.value()).contains("ADMIN:marta.admin", "Disabilitata la fonte ecommerce");
        }

        JsonNode rejected = send("POST", "/v1/events", null, purchase("SRC-OFF-1"), 202);
        assertThat(rejected.path("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.path("rejectCode").asString()).isEqualTo("SOURCE_DISABLED");

        send("PUT", "/v1/sources/ecommerce", ADMIN, Map.of("enabled", true), 200);
        assertThat(send("POST", "/v1/events", null, purchase("SRC-ON-2"), 202).path("status").asString()).isEqualTo("ACCEPTED");
    }

    @Test
    void allowedTypesAreValidatedAndApplied() {
        JsonNode problem = send("PUT", "/v1/sources/partner", ADMIN, Map.of("allowedTypes", List.of("quiz.completed", "nope.none")), 422);
        assertThat(problem.path("code").asString()).isEqualTo("SOURCE_INVALID");
        assertThat(problem.path("errors").get(0).path("field").asString()).isEqualTo("allowedTypes[1]");
        assertThat(send("PUT", "/v1/sources/partner", ADMIN, Map.of(), 422).path("code").asString()).isEqualTo("SOURCE_INVALID");
        send("PUT", "/v1/sources/ghost", ADMIN, Map.of("enabled", false), 404);

        JsonNode narrowed = send("PUT", "/v1/sources/partner", ADMIN, Map.of("allowedTypes", List.of("quiz.completed")), 200);
        assertThat(narrowed.path("allowedTypes").toString()).isEqualTo("[\"quiz.completed\"]");
        assertThat(narrowed.path("enabled").asBoolean()).as("enabled invariato").isTrue();
        Map<String, Object> survey = event("SRC-PARTNER-1", "partner", "survey.completed", Map.of("surveyId", "SRV-1"));
        JsonNode notAllowed = send("POST", "/v1/events", null, survey, 202);
        assertThat(notAllowed.path("rejectCode").asString()).isEqualTo("TYPE_NOT_ALLOWED");
        send("PUT", "/v1/sources/partner", ADMIN, Map.of("allowedTypes", List.of("survey.completed", "quiz.completed")), 200);
    }

    // ---------- supporto ----------

    private JsonNode source(String code) {
        for (JsonNode s : send("GET", "/v1/sources", null, null, 200)) {
            if (code.equals(s.path("code").asString())) {
                return s;
            }
        }
        throw new AssertionError("fonte non trovata: " + code);
    }

    private Map<String, Object> purchase(String id) {
        return event(id, "ecommerce", "purchase.completed",
                Map.of("orderId", "ORD-" + id, "amount", 130, "currency", "EUR", "channel", "ONLINE"));
    }

    private Map<String, Object> event(String id, String source, String type, Map<String, Object> data) {
        Map<String, Object> event = new HashMap<>();
        event.put("specversion", "1.0");
        event.put("id", id);
        event.put("source", "urn:loyaltyhub:source:" + source);
        event.put("type", type);
        event.put("subject", "member:MBR-000002");
        event.put("time", Instant.now().toString());
        event.put("data", data);
        return event;
    }

    private JsonNode send(String method, String path, String actor, Object body, int expected) {
        var spec = RestClient.create("http://localhost:" + port).method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(path);
        if (actor != null) {
            spec = spec.header("X-LH-Actor", actor);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes());
            assertThat(res.getStatusCode().value()).as(method + " " + path + " → " + text).isEqualTo(expected);
            return text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text);
        });
    }

    private KafkaConsumer<String, String> consumer(String group) {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, System.getProperty("spring.embedded.kafka.brokers"),
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
    }

    private static ConsumerRecord<String, String> poll(KafkaConsumer<String, String> consumer,
                                                       java.util.function.Predicate<ConsumerRecord<String, String>> match) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(400))) {
                if (match.test(r)) {
                    return r;
                }
            }
        }
        return null;
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
