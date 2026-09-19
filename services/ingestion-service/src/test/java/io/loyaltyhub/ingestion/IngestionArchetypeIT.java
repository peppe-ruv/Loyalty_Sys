package io.loyaltyhub.ingestion;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Accettazione dell'archetipo (M0.5, docs/servizi/ingestion-service.md §7): un evento valido riceve
 * {@code 202 ACCEPTED} ed è pubblicato su {@code lh.actions.v1} con chiave = memberId e {@code lhhop=0};
 * lo stesso {@code source+id} inviato due volte dà {@code DUPLICATE} e un solo record sul topic.
 * Senza Docker: Spring Boot su EmbeddedKafka + Postgres in-process (Zonky).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.dlq.v1"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestionArchetypeIT {

    private static final String ACTIONS = "lh.actions.v1";
    private static final EmbeddedPostgres PG = startPg();

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        // L'app (e Flyway) usano currentSchema=ingestion: le tabelle vivono lì.
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
    void validEventIsAcceptedAndPublishedWithMemberKey() {
        Map<String, Object> event = cloudEvent("01K0AAAAAAAAAAAAAAAAAAAA01", "member:MBR-000002");

        ResponseEntity<JsonNode> response = client().post().uri("/v1/events")
                .contentType(MediaType.APPLICATION_JSON).body(event).retrieve().toEntity(JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(response.getBody().path("eventId").asText()).isEqualTo("01K0AAAAAAAAAAAAAAAAAAAA01");

        try (KafkaConsumer<String, String> consumer = consumer("accept-check")) {
            consumer.subscribe(List.of(ACTIONS));
            ConsumerRecord<String, String> rec = poll(consumer, r -> r.key().equals("MBR-000002"));
            assertThat(rec).as("azione pubblicata sul topic").isNotNull();
            JsonNode published = readJson(rec.value());
            assertThat(published.path("type").asText()).isEqualTo("io.loyaltyhub.action.purchase.completed");
            assertThat(published.path("lhhop").asInt()).isZero();
            assertThat(published.path("lhcorrelationid").asText()).isEqualTo("01K0AAAAAAAAAAAAAAAAAAAA01");
        }
    }

    @Test
    void sameSourceAndIdTwiceIsDuplicateWithSingleRecord() {
        Map<String, Object> event = cloudEvent("01K0BBBBBBBBBBBBBBBBBBBB02", "member:MBR-000005");

        ResponseEntity<JsonNode> first = client().post().uri("/v1/events")
                .contentType(MediaType.APPLICATION_JSON).body(event).retrieve().toEntity(JsonNode.class);
        ResponseEntity<JsonNode> second = client().post().uri("/v1/events")
                .contentType(MediaType.APPLICATION_JSON).body(event).retrieve().toEntity(JsonNode.class);

        assertThat(first.getBody().path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(second.getBody().path("status").asText()).isEqualTo("DUPLICATE");

        try (KafkaConsumer<String, String> consumer = consumer("dup-check")) {
            consumer.subscribe(List.of(ACTIONS));
            int count = 0;
            long deadline = System.currentTimeMillis() + 8_000;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> recs = consumer.poll(Duration.ofMillis(400));
                for (ConsumerRecord<String, String> r : recs) {
                    if (r.key().equals("MBR-000005")) {
                        count++;
                    }
                }
            }
            assertThat(count).as("un solo record malgrado il doppio invio").isEqualTo(1);
        }
    }

    @Test
    void malformedEventIsBadRequest() {
        // Manca `data`: errore di forma → 400, niente sul topic.
        Map<String, Object> bad = Map.of(
                "specversion", "1.0", "id", "01K0CCCC", "source", "urn:loyaltyhub:source:app",
                "type", "app.login.daily", "subject", "member:MBR-000002", "time", "2026-09-19T07:05:00Z");
        try {
            client().post().uri("/v1/events").contentType(MediaType.APPLICATION_JSON)
                    .body(bad).retrieve().toEntity(JsonNode.class);
            fail("atteso 400");
        } catch (RestClientResponseException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(400);
            JsonNode body = e.getResponseBodyAs(JsonNode.class);
            assertThat(body.path("code").asText()).isEqualTo("BAD_REQUEST");
        }
    }

    // ---------- helper ----------

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private Map<String, Object> cloudEvent(String id, String subject) {
        return Map.of(
                "specversion", "1.0",
                "id", id,
                "source", "urn:loyaltyhub:source:ecommerce",
                "type", "purchase.completed",
                "subject", subject,
                "time", "2026-09-19T10:15:00Z",
                "data", Map.of("orderId", "ORD-" + id, "amount", 130, "currency", "EUR", "channel", "ONLINE"));
    }

    private KafkaConsumer<String, String> consumer(String group) {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, System.getProperty("spring.embedded.kafka.brokers"),
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
    }

    private ConsumerRecord<String, String> poll(KafkaConsumer<String, String> consumer,
                                                Predicate<ConsumerRecord<String, String>> match) {
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

    private JsonNode readJson(String value) {
        try {
            return mapper.readTree(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
