package io.loyaltyhub.insight;

import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DLQ di insight (M7.3, F-INS-05, BO-27; docs/servizi/insight-service.md §3, §5, §7): un record su {@code lh.dlq.v1}
 * diventa una voce con i dati degli header {@code lh-*} (docs/04 §5), il tracciato è {@code FAILED}; elenco con
 * filtri; <em>scarta</em> e <em>riprocessa</em> solo ADMIN; il riprocessa di un'azione chiama
 * {@code ingestion POST /v1/events} (qui uno stub HTTP) con lo stesso id; effetti e fatti → {@code 409 NOT_REPROCESSABLE}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DlqIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String ADMIN = "ADMIN:marta.admin";
    private static final String MARKETING = "MARKETING:luca.mkt";

    /** Stub di ingestion: registra le richieste e risponde con lo {@code status} scelto dal test. */
    private static final BlockingQueue<Received> INGESTION_CALLS = new LinkedBlockingQueue<>();
    private static final AtomicReference<String> INGESTION_STATUS = new AtomicReference<>("ACCEPTED");
    private static final HttpServer INGESTION = startIngestionStub();

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    record Received(String path, String actor, String reprocess, JsonNode body) {
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=insight");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
        registry.add("loyaltyhub.insight.ingestion-url",
                () -> "http://localhost:" + INGESTION.getAddress().getPort() + "/");
    }

    @BeforeEach
    void resetStub() {
        INGESTION_CALLS.clear();
        INGESTION_STATUS.set("ACCEPTED");
    }

    @AfterAll
    void tearDown() throws Exception {
        INGESTION.stop(0);
        PG.close();
    }

    @Test
    void poisonedActionBecomesAnEntryWithHeaderFieldsAndTheTraceFails() {
        String action = envelope("EVT-POISON-1", "io.loyaltyhub.action.app.login.daily",
                "urn:loyaltyhub:source:app", "COR-POISON-1", Map.of("platform", "ANDROID", "_poison", true));
        publish("lh.actions.v1", action, Map.of());
        publish("lh.dlq.v1", action, dlqHeaders("lh.actions.v1", "lh-campaign", "DEMO_POISON",
                "io.loyaltyhub.common.kafka.NonRetryableEventException", "Azione avvelenata", "1", "false"));
        // Record DLQ riletto (stesso evento, stesso consumer): nessuna seconda voce aperta.
        publish("lh.dlq.v1", action, dlqHeaders("lh.actions.v1", "lh-campaign", "DEMO_POISON",
                "io.loyaltyhub.common.kafka.NonRetryableEventException", "Azione avvelenata", "1", "false"));

        JsonNode entry = awaitEntry("EVT-POISON-1");
        assertThat(entry.path("originalTopic").asString()).isEqualTo("lh.actions.v1");
        assertThat(entry.path("originalType").asString()).isEqualTo("io.loyaltyhub.action.app.login.daily");
        assertThat(entry.path("shortType").asString()).isEqualTo("app.login.daily");
        assertThat(entry.path("family").asString()).isEqualTo("ACTION");
        assertThat(entry.path("consumer").asString()).isEqualTo("lh-campaign");
        assertThat(entry.path("errorCode").asString()).isEqualTo("DEMO_POISON");
        assertThat(entry.path("errorClass").asString()).endsWith("NonRetryableEventException");
        assertThat(entry.path("errorMessage").asString()).isEqualTo("Azione avvelenata");
        assertThat(entry.path("errorStack").asString()).contains("\tat ");
        assertThat(entry.path("attempts").asInt()).isEqualTo(1);
        assertThat(entry.path("retryable").asBoolean(true)).isFalse();
        assertThat(entry.path("memberId").asString()).isEqualTo("MBR-000002");
        assertThat(entry.path("correlationId").asString()).isEqualTo("COR-POISON-1");
        assertThat(entry.path("status").asString()).isEqualTo("OPEN");
        assertThat(entry.path("reprocessable").asBoolean()).isTrue();
        assertThat(entry.path("payload").path("data").path("_poison").asBoolean()).isTrue();

        // Dettaglio per id.
        JsonNode detail = client().get().uri("/v1/dlq/" + entry.path("id").asString()).retrieve().body(JsonNode.class);
        assertThat(detail.path("eventId").asString()).isEqualTo("EVT-POISON-1");

        // SCN-POISON (§7): tracciato FAILED, con un nodo DLQ figlio dell'azione nella corsia del consumer.
        JsonNode trace = awaitTraceStatus("COR-POISON-1", "FAILED");
        assertThat(trace.path("status").asString()).isEqualTo("FAILED");
        assertThat(trace.path("outcome").path("dlq").asInt()).isEqualTo(1);
        JsonNode dlqNode = null;
        for (JsonNode n : trace.path("nodes")) {
            if ("DLQ".equals(n.path("family").asString())) {
                dlqNode = n;
            }
        }
        assertThat(dlqNode).isNotNull();
        assertThat(dlqNode.path("parentEventId").asString()).isEqualTo("EVT-POISON-1");
        assertThat(dlqNode.path("service").asString()).isEqualTo("campaign");

        // L'event store conserva l'azione originale (il record DLQ non la oscura).
        JsonNode stored = client().get().uri("/v1/events/EVT-POISON-1").retrieve().body(JsonNode.class);
        assertThat(stored.path("family").asString()).isEqualTo("ACTION");

        // Idempotenza: una sola voce per (evento, consumer).
        sleep(1_500);
        JsonNode page = client().get().uri("/v1/dlq?errorCode=DEMO_POISON&consumer=lh-campaign").retrieve().body(JsonNode.class);
        long count = 0;
        for (JsonNode i : page.path("items")) {
            if ("EVT-POISON-1".equals(i.path("eventId").asString())) {
                count++;
            }
        }
        assertThat(count).isEqualTo(1);
    }

    @Test
    void listFiltersByStatusConsumerAndErrorCode() {
        publish("lh.dlq.v1", envelope("EVT-FLT-1", "io.loyaltyhub.effect.coupon.issue",
                "urn:loyaltyhub:service:campaign", "COR-FLT-1", Map.of("rewardCode", "RWD-X")),
                dlqHeaders("lh.effects.v1", "lh-reward", "COUPON_POOL_EMPTY", "x.NonRetryableEventException",
                        "Pool vuoto", "1", "false"));
        publish("lh.dlq.v1", envelope("EVT-FLT-2", "io.loyaltyhub.fact.tier.upgraded",
                "urn:loyaltyhub:service:wallet", "COR-FLT-2", Map.of("newTier", "GOLD")),
                dlqHeaders("lh.facts.v1", "lh-ingestion", "LOOP_GUARD", "x.LoopGuardException", "lhhop 4", "1", "false"));
        awaitEntry("EVT-FLT-1");
        awaitEntry("EVT-FLT-2");

        JsonNode byCode = client().get().uri("/v1/dlq?errorCode=LOOP_GUARD").retrieve().body(JsonNode.class);
        assertThat(byCode.path("items").size()).isGreaterThanOrEqualTo(1);
        byCode.path("items").forEach(i -> assertThat(i.path("errorCode").asString()).isEqualTo("LOOP_GUARD"));
        assertThat(byCode.path("page").path("totalItems").asLong()).isEqualTo(byCode.path("items").size());

        JsonNode byConsumer = client().get().uri("/v1/dlq?consumer=lh-reward").retrieve().body(JsonNode.class);
        byConsumer.path("items").forEach(i -> assertThat(i.path("consumer").asString()).isEqualTo("lh-reward"));
        assertThat(byConsumer.path("items").size()).isGreaterThanOrEqualTo(1);

        JsonNode open = client().get().uri("/v1/dlq?status=open").retrieve().body(JsonNode.class);
        open.path("items").forEach(i -> assertThat(i.path("status").asString()).isEqualTo("OPEN"));
        JsonNode none = client().get().uri("/v1/dlq?status=DISCARDED&errorCode=NO_SUCH_CODE").retrieve().body(JsonNode.class);
        assertThat(none.path("items").size()).isZero();
    }

    @Test
    void discardNeedsAdminAndANoteAndIsAudited() {
        publish("lh.dlq.v1", envelope("EVT-DSC-1", "io.loyaltyhub.fact.tier.upgraded",
                "urn:loyaltyhub:service:wallet", "COR-DSC-1", Map.of("newTier", "GOLD")),
                dlqHeaders("lh.facts.v1", "lh-ingestion", "LOOP_GUARD", "x.LoopGuardException", "lhhop 4", "1", "false"));
        String id = awaitEntry("EVT-DSC-1").path("id").asString();

        ResponseEntity<JsonNode> forbidden = post("/v1/dlq/" + id + "/discard", MARKETING, Map.of("note", "no"));
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);

        ResponseEntity<JsonNode> noNote = post("/v1/dlq/" + id + "/discard", ADMIN, Map.of());
        assertThat(noNote.getStatusCode().value()).isEqualTo(422);
        assertThat(noNote.getBody().path("code").asString()).isEqualTo("NOTE_REQUIRED");

        ResponseEntity<JsonNode> ok = post("/v1/dlq/" + id + "/discard", ADMIN, Map.of("note", "Catena interna troppo lunga"));
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody().path("status").asString()).isEqualTo("DISCARDED");
        assertThat(ok.getBody().path("resolvedBy").asString()).isEqualTo(ADMIN);
        assertThat(ok.getBody().path("resolutionNote").asString()).isEqualTo("Catena interna troppo lunga");
        assertThat(ok.getBody().path("resolvedAt").isMissingNode()).isFalse();

        ResponseEntity<JsonNode> again = post("/v1/dlq/" + id + "/discard", ADMIN, Map.of("note", "ancora"));
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(again.getBody().path("code").asString()).isEqualTo("DLQ_NOT_OPEN");

        // Un fatto scartato resta un fallimento: il tracciato è FAILED.
        assertThat(awaitTraceStatus("COR-DSC-1", "FAILED").path("status").asString()).isEqualTo("FAILED");

        // Audit della chiusura (lh.audit.v1 → audit_entry).
        JsonNode audit = awaitAudit(id);
        assertThat(audit.path("items").size()).isEqualTo(1);
        assertThat(audit.path("items").get(0).path("actorName").asString()).isEqualTo("marta.admin");
        assertThat(audit.path("items").get(0).path("after").path("status").asString()).isEqualTo("DISCARDED");
    }

    @Test
    void reprocessOfAnActionResendsItToIngestionWithTheSameId() throws Exception {
        String action = envelope("EVT-RPR-1", "io.loyaltyhub.action.purchase.completed",
                "urn:loyaltyhub:source:ecommerce", "COR-RPR-1", Map.of("orderId", "ORD-RPR", "amount", 42));
        publish("lh.actions.v1", action, Map.of());
        publish("lh.dlq.v1", action, dlqHeaders("lh.actions.v1", "lh-campaign", "IllegalStateException",
                "java.lang.IllegalStateException", "boom", "4", "true"));
        String id = awaitEntry("EVT-RPR-1").path("id").asString();
        assertThat(awaitTraceStatus("COR-RPR-1", "FAILED").path("status").asString()).isEqualTo("FAILED");

        ResponseEntity<JsonNode> forbidden = post("/v1/dlq/" + id + "/reprocess", MARKETING, null);
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);
        assertThat(INGESTION_CALLS).isEmpty();

        ResponseEntity<JsonNode> ok = post("/v1/dlq/" + id + "/reprocess", ADMIN, null);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody().path("status").asString()).isEqualTo("REPROCESSED");
        assertThat(ok.getBody().path("resolvedBy").asString()).isEqualTo(ADMIN);

        Received call = INGESTION_CALLS.poll(5, TimeUnit.SECONDS);
        assertThat(call).isNotNull();
        assertThat(call.path()).isEqualTo("/v1/events");
        assertThat(call.actor()).isEqualTo(ADMIN);
        assertThat(call.reprocess()).isEqualTo(id);
        assertThat(call.body().path("id").asString()).isEqualTo("EVT-RPR-1");
        assertThat(call.body().path("type").asString()).isEqualTo("io.loyaltyhub.action.purchase.completed");
        assertThat(call.body().path("source").asString()).isEqualTo("urn:loyaltyhub:source:ecommerce");
        assertThat(call.body().path("subject").asString()).isEqualTo("member:MBR-000002");
        assertThat(call.body().path("data").path("amount").asInt()).isEqualTo(42);
        assertThat(call.body().has("lhcorrelationid")).as("gli attributi lh* li rimette ingestion").isFalse();

        // Riprocessata: il tracciato non è più FAILED per questa voce.
        JsonNode trace = client().get().uri("/v1/traces/COR-RPR-1").retrieve().body(JsonNode.class);
        assertThat(trace.path("status").asString()).isNotEqualTo("FAILED");

        // Una seconda volta: la voce è chiusa.
        ResponseEntity<JsonNode> again = post("/v1/dlq/" + id + "/reprocess", ADMIN, null);
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(again.getBody().path("code").asString()).isEqualTo("DLQ_NOT_OPEN");
    }

    @Test
    void reprocessRefusedByIngestionLeavesTheEntryOpen() {
        String action = envelope("EVT-RPR-2", "io.loyaltyhub.action.app.login.daily",
                "urn:loyaltyhub:source:app", "COR-RPR-2", Map.of("platform", "IOS"));
        publish("lh.dlq.v1", action, dlqHeaders("lh.actions.v1", "lh-gamification", "IllegalStateException",
                "java.lang.IllegalStateException", "boom", "4", "true"));
        String id = awaitEntry("EVT-RPR-2").path("id").asString();

        INGESTION_STATUS.set("DUPLICATE");
        ResponseEntity<JsonNode> refused = post("/v1/dlq/" + id + "/reprocess", ADMIN, null);
        assertThat(refused.getStatusCode().value()).isEqualTo(409);
        assertThat(refused.getBody().path("code").asString()).isEqualTo("REPROCESS_REJECTED");
        JsonNode entry = client().get().uri("/v1/dlq/" + id).retrieve().body(JsonNode.class);
        assertThat(entry.path("status").asString()).isEqualTo("OPEN");
    }

    @Test
    void effectsAndFactsAreNotReprocessable() {
        publish("lh.dlq.v1", envelope("EVT-NRP-1", "io.loyaltyhub.effect.points.grant",
                "urn:loyaltyhub:service:campaign", "COR-NRP-1", Map.of("amount", 10, "currency", "PTS")),
                dlqHeaders("lh.effects.v1", "lh-wallet", "IllegalStateException", "java.lang.IllegalStateException",
                        "boom", "4", "true"));
        JsonNode entry = awaitEntry("EVT-NRP-1");
        assertThat(entry.path("family").asString()).isEqualTo("EFFECT");
        assertThat(entry.path("reprocessable").asBoolean()).isFalse();

        ResponseEntity<JsonNode> resp = post("/v1/dlq/" + entry.path("id").asString() + "/reprocess", ADMIN, null);
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody().path("code").asString()).isEqualTo("NOT_REPROCESSABLE");
        assertThat(INGESTION_CALLS).isEmpty();
    }

    @Test
    void nonJsonDlqValueIsKeptAsRaw() {
        publish("lh.dlq.v1", "questo non è json", dlqHeaders("lh.actions.v1", "lh-member", "JacksonException",
                "tools.jackson.core.JacksonException", "Unrecognized token", "4", "true"));
        JsonNode page = null;
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode found = null;
        while (found == null && System.currentTimeMillis() < deadline) {
            page = client().get().uri("/v1/dlq?consumer=lh-member").retrieve().body(JsonNode.class);
            for (JsonNode i : page.path("items")) {
                if ("questo non è json".equals(i.path("payload").path("raw").asString())) {
                    found = i;
                }
            }
            sleep(400);
        }
        assertThat(found).as("voce DLQ col valore grezzo").isNotNull();
        assertThat(found.path("family").asString()).isEqualTo("ACTION"); // dal topic d'origine
        assertThat(found.path("errorCode").asString()).isEqualTo("JacksonException");
    }

    // ---------- helper ----------

    private JsonNode awaitEntry(String eventId) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode page = client().get().uri("/v1/dlq?size=200").retrieve().body(JsonNode.class);
            for (JsonNode i : page.path("items")) {
                if (eventId.equals(i.path("eventId").asString())) {
                    return i;
                }
            }
            sleep(400);
        }
        throw new AssertionError("Nessuna voce DLQ per " + eventId);
    }

    private JsonNode awaitTraceStatus(String correlationId, String status) {
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode trace = null;
        while (System.currentTimeMillis() < deadline) {
            trace = client().get().uri("/v1/traces/" + correlationId).retrieve().body(JsonNode.class);
            if (trace != null && status.equals(trace.path("status").asString())) {
                return trace;
            }
            sleep(400);
        }
        return trace;
    }

    private JsonNode awaitAudit(String entityId) {
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode page = null;
        while (System.currentTimeMillis() < deadline) {
            page = client().get().uri("/v1/audit?entityType=DlqEntry&entityId=" + entityId).retrieve().body(JsonNode.class);
            if (page != null && page.path("items").size() >= 1) {
                return page;
            }
            sleep(400);
        }
        return page;
    }

    private ResponseEntity<JsonNode> post(String path, String actor, Object body) {
        RestClient.RequestBodySpec spec = client().post().uri(path).header("X-LH-Actor", actor)
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            spec.body(body);
        }
        return spec.retrieve().onStatus(HttpStatusCode::isError, (req, res) -> {
        }).toEntity(JsonNode.class);
    }

    private Map<String, String> dlqHeaders(String topic, String consumer, String code, String errorClass,
                                           String message, String attempts, String retryable) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("lh-original-topic", topic);
        h.put("lh-consumer", consumer);
        h.put("lh-error-code", code);
        h.put("lh-error-class", errorClass);
        h.put("lh-error-message", message);
        h.put("lh-attempts", attempts);
        h.put("lh-error-retryable", retryable);
        h.put("lh-error-stack", errorClass + ": " + message + "\n\tat io.loyaltyhub.Test.run(Test.java:1)");
        return h;
    }

    private String envelope(String id, String type, String source, String correlationId, Map<String, Object> data) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("specversion", "1.0");
        env.put("id", id);
        env.put("source", source);
        env.put("type", type);
        env.put("subject", "member:MBR-000002");
        env.put("time", "2026-09-15T10:00:00Z");
        env.put("lhcorrelationid", correlationId);
        env.put("lhhop", 0);
        env.put("data", data);
        return mapper.writeValueAsString(env);
    }

    private void publish(String topic, String value, Map<String, String> headers) {
        Properties props = new Properties();
        props.put("bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"));
        try (KafkaProducer<String, String> producer =
                     new KafkaProducer<>(props, new StringSerializer(), new StringSerializer())) {
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, "MBR-000002", value);
            headers.forEach((k, v) -> record.headers().add(k, v.getBytes(StandardCharsets.UTF_8)));
            producer.send(record);
            producer.flush();
        }
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static HttpServer startIngestionStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            ObjectMapper json = new ObjectMapper();
            server.createContext("/", exchange -> {
                byte[] in = exchange.getRequestBody().readAllBytes();
                JsonNode body = json.readTree(new String(in, StandardCharsets.UTF_8));
                INGESTION_CALLS.add(new Received(exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("X-LH-Actor"),
                        exchange.getRequestHeaders().getFirst("X-LH-Reprocess"), body));
                String reply = json.writeValueAsString(Map.of("eventId", body.path("id").asString(),
                        "status", INGESTION_STATUS.get(), "correlationId", body.path("id").asString()));
                byte[] out = reply.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(202, out.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                } catch (IOException ignored) {
                    // client già chiuso
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
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
