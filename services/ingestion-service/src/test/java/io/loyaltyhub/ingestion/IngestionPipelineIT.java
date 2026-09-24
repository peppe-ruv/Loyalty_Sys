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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Pipeline di accettazione completa (M1.1, docs/servizi/ingestion-service.md §5, §7). Col profilo
 * {@code demo} i registri (fonti, tipi con JSON Schema, indice membri) sono caricati dai seed canonici.
 * Verifica gli otto esiti: accettato, duplicato, fonte disabilitata, tipo sconosciuto, tipo non ammesso,
 * dati non validi, tempo non valido, non abbinato, membro non attivo — più forma non valida (400).
 * Senza Docker: Spring Boot su EmbeddedKafka + Postgres in-process (Zonky).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestionPipelineIT {

    private static final String ACTIONS = "lh.actions.v1";
    private static final EmbeddedPostgres PG = startPg();

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

    // ---------- esito ACCEPTED ----------

    @Test
    void validEventIsAcceptedAndPublishedWithMemberKey() {
        Map<String, Object> event = purchase("01K0AAAAAAAAAAAAAAAAAAAA01", "member:MBR-000002");

        JsonNode body = post(event, 202);
        assertThat(body.path("status").asString()).isEqualTo("ACCEPTED");
        assertThat(body.path("eventId").asString()).isEqualTo("01K0AAAAAAAAAAAAAAAAAAAA01");
        assertThat(body.path("memberId").asString()).isEqualTo("MBR-000002");

        try (KafkaConsumer<String, String> consumer = consumer("accept-check")) {
            consumer.subscribe(List.of(ACTIONS));
            ConsumerRecord<String, String> rec = poll(consumer, r -> r.key().equals("MBR-000002"));
            assertThat(rec).as("azione pubblicata sul topic").isNotNull();
            JsonNode published = readJson(rec.value());
            assertThat(published.path("type").asString()).isEqualTo("io.loyaltyhub.action.purchase.completed");
            assertThat(published.path("source").asString()).isEqualTo("urn:loyaltyhub:source:ecommerce");
            assertThat(published.path("subject").asString()).isEqualTo("member:MBR-000002");
            assertThat(published.path("lhhop").asInt()).isZero();
            assertThat(published.path("lhcorrelationid").asString()).isEqualTo("01K0AAAAAAAAAAAAAAAAAAAA01");
        }
    }

    @Test
    void memberResolvedByExternalId() {
        Map<String, Object> event = purchase("01K0AAAAAAAAAAAAAAAAAAAA02", "external:CRM-103");
        JsonNode body = post(event, 202);
        assertThat(body.path("status").asString()).isEqualTo("ACCEPTED");
        assertThat(body.path("memberId").asString()).isEqualTo("MBR-000003");
    }

    // ---------- POST /v1/transactions (F-ING-07) ----------

    @Test
    void transactionBecomesPurchaseCompletedWithTxnIdAndIsDeduplicated() {
        Map<String, Object> txn = Map.of("source", "ecommerce", "orderId", "ORD-77001", "memberRef", "member:MBR-000007",
                "amount", 130, "currency", "EUR", "channel", "ONLINE",
                "items", List.of(Map.of("sku", "SKU-1", "category", "casa", "quantity", 1, "unitPrice", 130)));

        JsonNode first = postTransaction(txn, 202);
        assertThat(first.path("status").asString()).isEqualTo("ACCEPTED");
        assertThat(first.path("eventId").asString()).isEqualTo("txn-ORD-77001");
        assertThat(first.path("memberId").asString()).isEqualTo("MBR-000007");
        assertThat(postTransaction(txn, 202).path("status").asString()).isEqualTo("DUPLICATE");

        try (KafkaConsumer<String, String> consumer = consumer("txn-check")) {
            consumer.subscribe(List.of(ACTIONS));
            ConsumerRecord<String, String> rec = poll(consumer, r -> r.value().contains("txn-ORD-77001"));
            assertThat(rec).as("azione della transazione").isNotNull();
            JsonNode published = readJson(rec.value());
            assertThat(published.path("type").asString()).isEqualTo("io.loyaltyhub.action.purchase.completed");
            assertThat(published.path("data").path("amount").asInt()).isEqualTo(130);
            assertThat(published.path("data").path("items").size()).isEqualTo(1);
        }
    }

    @Test
    void transactionReturnBecomesPurchaseReturned() {
        JsonNode body = postTransaction(Map.of("source", "ecommerce", "orderId", "ORD-77002", "memberRef", "member:MBR-000007",
                "amount", 24.9, "kind", "RETURN"), 202);
        assertThat(body.path("status").asString()).isEqualTo("ACCEPTED");
        assertThat(body.path("eventId").asString()).isEqualTo("txn-return-ORD-77002");
    }

    @Test
    void transactionWithMissingFieldsIs400AndUnknownMemberIsUnmatched() {
        postTransaction(Map.of("source", "ecommerce", "memberRef", "member:MBR-000007", "amount", 10), 400);
        JsonNode unmatched = postTransaction(Map.of("source", "ecommerce", "orderId", "ORD-77003",
                "memberRef", "external:NOPE-1", "amount", 10, "currency", "EUR"), 202);
        assertThat(unmatched.path("status").asString()).isEqualTo("UNMATCHED");
    }

    @Test
    void sameSourceAndIdTwiceIsDuplicateWithSingleRecord() {
        Map<String, Object> event = purchase("01K0BBBBBBBBBBBBBBBBBBBB02", "member:MBR-000005");

        JsonNode first = post(event, 202);
        JsonNode second = post(event, 202);
        assertThat(first.path("status").asString()).isEqualTo("ACCEPTED");
        assertThat(second.path("status").asString()).isEqualTo("DUPLICATE");

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

    // ---------- riprocessa DLQ (M7.3, ADR-002 eccezione 1) ----------

    @Test
    void reprocessHeaderRepublishesTheAcceptedActionWithTheSameIdOnlyForAdmin() {
        String id = "01K0RRRRRRRRRRRRRRRRRRRR01";
        Map<String, Object> event = purchase(id, "member:MBR-000004");
        assertThat(post(event, 202).path("status").asString()).isEqualTo("ACCEPTED");

        // Solo su comando di un ADMIN.
        postReprocess(event, "MARKETING:luca.mkt", 403);
        // Stesso id: la dedup normale direbbe DUPLICATE, il riprocessa ripubblica l'envelope accettato.
        JsonNode replayed = postReprocess(event, "ADMIN:marta.admin", 202);
        assertThat(replayed.path("status").asString()).isEqualTo("ACCEPTED");
        assertThat(replayed.path("eventId").asString()).isEqualTo(id);
        assertThat(replayed.path("correlationId").asString()).isEqualTo(id);
        assertThat(replayed.path("memberId").asString()).isEqualTo("MBR-000004");
        // Senza l'header resta la dedup.
        assertThat(post(event, 202).path("status").asString()).isEqualTo("DUPLICATE");

        try (KafkaConsumer<String, String> consumer = consumer("reprocess-check")) {
            consumer.subscribe(List.of(ACTIONS));
            List<JsonNode> copies = new java.util.ArrayList<>();
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline && copies.size() < 2) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(400))) {
                    if (r.value().contains(id)) {
                        copies.add(readJson(r.value()));
                    }
                }
            }
            assertThat(copies).as("l'originale e la ripubblicazione").hasSize(2);
            assertThat(copies.get(1).path("id").asString()).isEqualTo(id);
            assertThat(copies.get(1).path("lhcorrelationid").asString()).isEqualTo(id);
            assertThat(copies.get(1).path("lhhop").asInt()).isZero();
            assertThat(copies.get(1).path("subject").asString()).isEqualTo("member:MBR-000004");
        }
    }

    // ---------- rifiuti di business (202 + status) ----------

    @Test
    void unknownSourceIsRejected() {
        Map<String, Object> event = purchase("01K0CCCCCCCCCCCCCCCCCCCC01", "member:MBR-000002");
        event.put("source", "urn:loyaltyhub:source:ghost");
        JsonNode body = post(event, 202);
        assertReject(body, "SOURCE_DISABLED");
    }

    @Test
    void unknownTypeIsRejected() {
        Map<String, Object> event = purchase("01K0CCCCCCCCCCCCCCCCCCCC02", "member:MBR-000002");
        event.put("type", "mystery.event");
        JsonNode body = post(event, 202);
        assertReject(body, "UNKNOWN_TYPE");
    }

    @Test
    void typeNotAllowedForSourceIsRejected() {
        // app.login.daily esiste ma non è ammesso per la fonte ecommerce.
        Map<String, Object> event = new HashMap<>(Map.of(
                "specversion", "1.0",
                "id", "01K0CCCCCCCCCCCCCCCCCCCC03",
                "source", "urn:loyaltyhub:source:ecommerce",
                "type", "app.login.daily",
                "subject", "member:MBR-000002",
                "time", Instant.now().toString(),
                "data", Map.of("platform", "IOS")));
        JsonNode body = post(event, 202);
        assertReject(body, "TYPE_NOT_ALLOWED");
    }

    @Test
    void invalidDataIsRejectedWithSchemaDetail() {
        Map<String, Object> event = new HashMap<>(Map.of(
                "specversion", "1.0",
                "id", "01K0CCCCCCCCCCCCCCCCCCCC04",
                "source", "urn:loyaltyhub:source:ecommerce",
                "type", "purchase.completed",
                "subject", "member:MBR-000002",
                "time", Instant.now().toString(),
                "data", Map.of("orderId", "ORD-1", "currency", "EUR"))); // manca amount (required)
        JsonNode body = post(event, 202);
        assertReject(body, "INVALID_DATA");
        assertThat(body.path("detail").asString()).contains("amount");
    }

    @Test
    void futureTimeIsRejected() {
        Map<String, Object> event = purchase("01K0CCCCCCCCCCCCCCCCCCCC05", "member:MBR-000002");
        event.put("time", Instant.now().plus(Duration.ofDays(2)).toString());
        JsonNode body = post(event, 202);
        assertReject(body, "INVALID_TIME");
    }

    @Test
    void unknownMemberIsUnmatched() {
        Map<String, Object> event = purchase("01K0CCCCCCCCCCCCCCCCCCCC06", "member:MBR-999999");
        JsonNode body = post(event, 202);
        assertThat(body.path("status").asString()).isEqualTo("UNMATCHED");
    }

    @Test
    void blockedMemberIsRejected() {
        Map<String, Object> event = purchase("01K0CCCCCCCCCCCCCCCCCCCC07", "member:MBR-000008");
        JsonNode body = post(event, 202);
        assertReject(body, "MEMBER_NOT_ACTIVE");
        assertThat(body.path("memberId").asString()).isEqualTo("MBR-000008");
    }

    // ---------- reset demo (M1.7) ----------

    @Test
    void demoResetIsExposedAndReloadsSeed() {
        JsonNode body = client().post().uri("/v1/demo/reset")
                .header("X-LH-Actor", "ADMIN:test")
                .retrieve().body(JsonNode.class);
        assertThat(body.path("status").asString()).isEqualTo("OK");
        boolean hasIngestion = false;
        for (JsonNode n : body.path("reset")) {
            if (n.asString().equals("ingestion")) {
                hasIngestion = true;
            }
        }
        assertThat(hasIngestion).as("il seeder di ingestion è tra i componenti resettati").isTrue();
    }

    // ---------- scenari (M2.7) ----------

    @Test
    void scenarioRunsThroughPipelineWithExpectedOutcomes() {
        JsonNode list = client().get().uri("/v1/demo/scenarios").retrieve().body(JsonNode.class);
        assertThat(list.size()).isGreaterThanOrEqualTo(3);

        JsonNode started = client().post().uri("/v1/demo/scenarios/SCN-MIXED-DAY/run")
                .header("X-LH-Actor", "ADMIN:test").retrieve().body(JsonNode.class);
        String runId = started.path("runId").asString();
        assertThat(runId).isNotBlank();

        JsonNode run = awaitScenarioDone(runId);
        assertThat(run.path("status").asString()).isEqualTo("DONE");
        assertThat(run.path("stepsDone").asInt()).isEqualTo(3);

        JsonNode results = run.path("results");
        assertThat(results.size()).isEqualTo(3);
        // I primi due passi sono ACCEPTED e portano un correlationId; il terzo (membro inesistente) è UNMATCHED come atteso.
        assertThat(results.get(0).path("status").asString()).isEqualTo("ACCEPTED");
        assertThat(results.get(0).path("correlationId").asString()).isNotBlank();
        assertThat(results.get(0).path("ok").asBoolean()).isTrue();
        JsonNode last = results.get(2);
        assertThat(last.path("status").asString()).isEqualTo("UNMATCHED");
        assertThat(last.path("expected").asString()).isEqualTo("UNMATCHED");
        assertThat(last.path("ok").asBoolean()).isTrue();
    }

    /** docs/10 §8: SCN-DUPLICATE ripetibile (id {run}) e SCN-BAD-EVENT con i quattro esiti negativi. */
    @Test
    void duplicateAndBadEventScenariosMatchDocs10() {
        for (int run = 0; run < 2; run++) {
            JsonNode dup = runScenario("SCN-DUPLICATE");
            assertThat(dup.path("status").asString()).isEqualTo("DONE");
            JsonNode steps = dup.path("results");
            assertThat(steps.get(0).path("status").asString()).as("esecuzione " + run).isEqualTo("ACCEPTED");
            assertThat(steps.get(1).path("status").asString()).isEqualTo("DUPLICATE");
            assertThat(steps.get(1).path("eventId").asString()).isEqualTo(steps.get(0).path("eventId").asString());
        }

        JsonNode bad = runScenario("SCN-BAD-EVENT").path("results");
        assertThat(bad.size()).isEqualTo(4);
        List<String> outcomes = new java.util.ArrayList<>();
        bad.forEach(r -> {
            assertThat(r.path("ok").asBoolean()).as(r.toString()).isTrue();
            outcomes.add(r.path("status").asString() + (r.hasNonNull("rejectCode") ? "/" + r.path("rejectCode").asString() : ""));
        });
        assertThat(outcomes).containsExactly("REJECTED/INVALID_DATA", "REJECTED/SOURCE_DISABLED", "UNMATCHED",
                "REJECTED/MEMBER_NOT_ACTIVE");
    }

    private JsonNode runScenario(String code) {
        String runId = client().post().uri("/v1/demo/scenarios/" + code + "/run")
                .header("X-LH-Actor", "ADMIN:test").retrieve().body(JsonNode.class).path("runId").asString();
        return awaitScenarioDone(runId);
    }

    private JsonNode awaitScenarioDone(String runId) {
        long deadline = System.currentTimeMillis() + 25_000;
        JsonNode run = null;
        while (System.currentTimeMillis() < deadline) {
            run = client().get().uri("/v1/demo/scenario-runs/" + runId).retrieve().body(JsonNode.class);
            if (run != null && !run.path("status").asString().equals("RUNNING")) {
                return run;
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return run;
    }

    // ---------- forma non valida (400) ----------

    @Test
    void malformedEventIsBadRequest() {
        Map<String, Object> bad = Map.of(
                "specversion", "1.0", "id", "01K0DDDD", "source", "urn:loyaltyhub:source:app",
                "type", "app.login.daily", "subject", "member:MBR-000002",
                "time", Instant.now().toString()); // manca data
        try {
            client().post().uri("/v1/events").contentType(MediaType.APPLICATION_JSON)
                    .body(bad).retrieve().toEntity(JsonNode.class);
            fail("atteso 400");
        } catch (RestClientResponseException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(400);
            JsonNode body = e.getResponseBodyAs(JsonNode.class);
            assertThat(body.path("code").asString()).isEqualTo("BAD_REQUEST");
        }
    }

    // ---------- helper ----------

    private void assertReject(JsonNode body, String rejectCode) {
        assertThat(body.path("status").asString()).isEqualTo("REJECTED");
        assertThat(body.path("rejectCode").asString()).isEqualTo(rejectCode);
    }

    private JsonNode postTransaction(Map<String, Object> txn, int expectedStatus) {
        return client().post().uri("/v1/transactions").contentType(MediaType.APPLICATION_JSON).body(txn)
                .exchange((req, res) -> {
                    assertThat(res.getStatusCode().value()).isEqualTo(expectedStatus);
                    return mapper.readTree(res.getBody());
                });
    }

    private JsonNode post(Map<String, Object> event, int expectedStatus) {
        ResponseEntity<JsonNode> response = client().post().uri("/v1/events")
                .contentType(MediaType.APPLICATION_JSON).body(event).retrieve().toEntity(JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
        return response.getBody();
    }

    private JsonNode postReprocess(Map<String, Object> event, String actor, int expectedStatus) {
        return client().post().uri("/v1/events").contentType(MediaType.APPLICATION_JSON)
                .header("X-LH-Actor", actor).header("X-LH-Reprocess", "DLQ-TEST").body(event)
                .exchange((req, res) -> {
                    assertThat(res.getStatusCode().value()).isEqualTo(expectedStatus);
                    return mapper.readTree(res.getBody());
                });
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private Map<String, Object> purchase(String id, String subject) {
        Map<String, Object> event = new HashMap<>();
        event.put("specversion", "1.0");
        event.put("id", id);
        event.put("source", "urn:loyaltyhub:source:ecommerce");
        event.put("type", "purchase.completed");
        event.put("subject", subject);
        event.put("time", Instant.now().toString());
        event.put("data", Map.of("orderId", "ORD-" + id, "amount", 130, "currency", "EUR", "channel", "ONLINE"));
        return event;
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
        return mapper.readTree(value);
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
