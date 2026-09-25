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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tipi azione custom da BO-09 (M6.7, F-ING-06, docs/servizi/ingestion-service.md §3): campi dedotti dallo schema,
 * creazione con permessi e validazione, azione accettata e pubblicata subito (nessun rilascio), schema aggiornato che
 * vale dall'azione successiva, tipi di sistema modificabili solo in nome/descrizione/icona/abilitazione da ADMIN.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.dlq.v1", "lh.audit.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventTypesIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String MARKETING = "MARKETING:luca.marketing";
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
    void fieldsAreFlattenedFromTheSchema() {
        JsonNode fields = send("GET", "/v1/event-types/purchase.completed/fields", null, null, 200);
        Map<String, JsonNode> byPath = new java.util.HashMap<>();
        fields.forEach(f -> byPath.put(f.path("path").asString(), f));
        assertThat(byPath.get("data.amount").path("type").asString()).isEqualTo("number");
        assertThat(byPath.get("data.amount").path("required").asBoolean()).isTrue();
        assertThat(byPath.get("data.channel").path("required").asBoolean()).isFalse();
        assertThat(byPath.get("data.channel").path("enum").toString()).isEqualTo("[\"ONLINE\",\"STORE\",\"APP\"]");
        assertThat(byPath.get("data.items[*].category").path("type").asString()).isEqualTo("string");
        assertThat(byPath).doesNotContainKey("data.items");

        JsonNode list = send("GET", "/v1/event-types", null, null, 200);
        JsonNode purchase = find(list, "purchase.completed");
        assertThat(purchase.path("dataSchema").isObject()).isTrue();
        assertThat(purchase.path("sampleData").path("orderId").asString()).isNotBlank();
        assertThat(purchase.path("description").asString()).isNotBlank();
        send("GET", "/v1/event-types/nope.none/fields", null, null, 404);
    }

    @Test
    void customTypeIsUsableRightAwayAndItsSchemaChangesAtRuntime() throws Exception {
        Map<String, Object> body = Map.of(
                "code", "meter.reading.sent", "name", "Autolettura inviata", "category", "SERVICE", "icon", "gauge",
                "dataSchema", Map.of("type", "object", "required", List.of("reading"), "properties", Map.of(
                        "reading", Map.of("type", "number", "minimum", 0),
                        "channel", Map.of("type", "string", "enum", List.of("APP", "WEB")))),
                "sampleData", Map.of("reading", 1234, "channel", "APP"));

        send("POST", "/v1/event-types", "ANALYST:sara.analyst", body, 403);
        send("POST", "/v1/event-types", "CARE:paolo.care", body, 403);
        JsonNode created = send("POST", "/v1/event-types", MARKETING, body, 201);
        assertThat(created.path("origin").asString()).isEqualTo("CUSTOM");
        assertThat(created.path("enabled").asBoolean()).isTrue();
        assertThat(send("POST", "/v1/event-types", MARKETING, body, 409).path("code").asString())
                .isEqualTo("EVENT_TYPE_EXISTS");
        JsonNode fields = send("GET", "/v1/event-types/meter.reading.sent/fields", null, null, 200);
        assertThat(fields.toString()).contains("data.reading", "data.channel");

        try (KafkaConsumer<String, String> consumer = consumer("event-types-it")) {
            consumer.subscribe(List.of("lh.actions.v1"));
            JsonNode fired = send("POST", "/v1/demo/simulator/fire", MARKETING,
                    Map.of("memberId", "MBR-000002", "type", "meter.reading.sent"), 200);
            assertThat(fired.get(0).path("status").asString()).isEqualTo("ACCEPTED");
            String eventId = fired.get(0).path("eventId").asString();
            JsonNode published = awaitAction(consumer, eventId);
            assertThat(published.path("type").asString()).isEqualTo("io.loyaltyhub.action.meter.reading.sent");
            // data assente → sample_data con piccole variazioni casuali (ingestion §3): reading 1234 ± 10 %.
            assertThat(published.path("data").path("reading").asInt()).isBetween(1110, 1358);
            assertThat(published.path("data").path("channel").asString()).isEqualTo("APP");
        }

        JsonNode bad = send("POST", "/v1/demo/simulator/fire", MARKETING,
                Map.of("memberId", "MBR-000002", "type", "meter.reading.sent", "data", Map.of("reading", -1)), 200);
        assertThat(bad.get(0).path("rejectCode").asString()).isEqualTo("INVALID_DATA");

        // Origine (ingestion §2: EXTERNAL | INTERNAL | SIMULATOR): ciò che parte dal simulatore è SIMULATOR, anche se scartato.
        JsonNode rows = send("GET", "/v1/inbound-events?type=meter.reading.sent", null, null, 200);
        assertThat(rows.size()).isGreaterThanOrEqualTo(2);
        rows.forEach(r -> assertThat(r.path("origin").asString()).as("origine di " + r.path("eventId")).isEqualTo("SIMULATOR"));

        // Schema aggiornato: vale dall'azione successiva (niente cache dello schema vecchio).
        Map<String, Object> stricter = new java.util.HashMap<>(body);
        stricter.remove("code");
        stricter.put("dataSchema", Map.of("type", "object", "required", List.of("reading", "meterId"), "properties",
                Map.of("reading", Map.of("type", "number"), "meterId", Map.of("type", "string"))));
        stricter.put("sampleData", Map.of("reading", 10, "meterId", "M-1"));
        send("PUT", "/v1/event-types/meter.reading.sent", MARKETING, stricter, 200);
        JsonNode missing = send("POST", "/v1/demo/simulator/fire", MARKETING,
                Map.of("memberId", "MBR-000002", "type", "meter.reading.sent", "data", Map.of("reading", 5)), 200);
        assertThat(missing.get(0).path("rejectCode").asString()).isEqualTo("INVALID_DATA");

        // Disabilitato → tipo sconosciuto in ingresso.
        stricter.put("enabled", false);
        send("PUT", "/v1/event-types/meter.reading.sent", ADMIN, stricter, 200);
        JsonNode off = send("POST", "/v1/demo/simulator/fire", MARKETING,
                Map.of("memberId", "MBR-000002", "type", "meter.reading.sent"), 200);
        assertThat(off.get(0).path("rejectCode").asString()).isEqualTo("UNKNOWN_TYPE");
    }

    @Test
    void invalidCustomTypesAreRejectedWithFieldErrors() {
        JsonNode badCode = send("POST", "/v1/event-types", MARKETING, Map.of("code", "Bad Code", "name", "x",
                "dataSchema", Map.of("type", "object")), 422);
        assertThat(fieldsOf(badCode)).contains("code");
        JsonNode badSample = send("POST", "/v1/event-types", MARKETING, Map.of("code", "survey.answered",
                "name", "Sondaggio", "dataSchema", Map.of("type", "object", "required", List.of("score"),
                        "properties", Map.of("score", Map.of("type", "integer"))),
                "sampleData", Map.of("score", "alto")), 422);
        assertThat(fieldsOf(badSample)).containsExactly("sampleData");
        JsonNode noSchema = send("POST", "/v1/event-types", MARKETING,
                Map.of("code", "survey.answered", "name", "Sondaggio"), 422);
        assertThat(fieldsOf(noSchema)).containsExactly("dataSchema");
    }

    @Test
    void systemTypesOnlyChangeLabelsAndOnlyByAdmin() {
        JsonNode before = send("GET", "/v1/event-types/app.login.daily", null, null, 200);
        Map<String, Object> rename = Map.of("name", "Accesso all'app", "description", "Login dall'app",
                "icon", "log-in", "enabled", true);
        send("PUT", "/v1/event-types/app.login.daily", MARKETING, rename, 403);
        JsonNode after = send("PUT", "/v1/event-types/app.login.daily", ADMIN, rename, 200);
        assertThat(after.path("name").asString()).isEqualTo("Accesso all'app");
        assertThat(after.path("origin").asString()).isEqualTo("SYSTEM");
        assertThat(after.path("dataSchema")).isEqualTo(before.path("dataSchema"));
        JsonNode locked = send("PUT", "/v1/event-types/app.login.daily", ADMIN,
                Map.of("name", "x", "dataSchema", Map.of("type", "object")), 422);
        assertThat(locked.path("code").asString()).isEqualTo("EVENT_TYPE_SYSTEM_LOCKED");
        assertThat(send("PUT", "/v1/event-types/app.login.daily", ADMIN, Map.of("code", "app.login.other", "name", "x"), 422)
                .path("code").asString()).isEqualTo("EVENT_TYPE_IMMUTABLE_FIELD");
    }

    // ---------- supporto ----------

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

    private static JsonNode find(JsonNode list, String code) {
        for (JsonNode t : list) {
            if (code.equals(t.path("code").asString())) {
                return t;
            }
        }
        throw new AssertionError("tipo non trovato: " + code);
    }

    private static List<String> fieldsOf(JsonNode problem) {
        List<String> out = new ArrayList<>();
        problem.path("errors").forEach(e -> out.add(e.path("field").asString()));
        return out;
    }

    private JsonNode awaitAction(KafkaConsumer<String, String> consumer, String eventId) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(400))) {
                JsonNode e = mapper.readTree(r.value());
                if (r.value().contains(eventId)) {
                    return e;
                }
            }
        }
        throw new AssertionError("azione non pubblicata: " + eventId);
    }

    private KafkaConsumer<String, String> consumer(String group) {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, System.getProperty("spring.embedded.kafka.brokers"),
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
