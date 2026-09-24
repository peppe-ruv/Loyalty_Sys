package io.loyaltyhub.member;

import io.loyaltyhub.common.event.JsonSchemaValidator;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Segmenti M6.6 (docs/servizi/member-service.md §3, §5, §7; docs/02 F-SEG-01/02/03; docs/10 §3): appartenenze dei seed,
 * anteprima {@code member.tier in [GOLD, PLATINUM]} = 4, ruoli {@code segment.write}, creazione/modifica/archiviazione
 * con i soli fatti di differenza (validi per il contratto EVT-FACT-06/07), statici, SCN-DIGITAL → etichette → ricalcolo
 * (Marco entra in SEG-DIGITAL ed esce da SEG-NOT-EBILL), reset con i {@code left} dei segmenti di prima.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "loyaltyhub.member.segments.reannounce-delay-ms=0")
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SegmentIT {

    private static final String FACTS = "lh.facts.v1";
    private static final String ACTIONS = "lh.actions.v1";
    private static final String ENTERED = "io.loyaltyhub.fact.member.segment.entered";
    private static final String LEFT = "io.loyaltyhub.fact.member.segment.left";
    private static final String MARKETING = "MARKETING:luca.marketing";
    private static final EmbeddedPostgres PG = startPg();

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JsonSchemaValidator validator;

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
    @Order(1)
    void seedMembershipsFollowDocs10() {
        JsonNode list = get("/v1/segments");
        assertThat(list.path("page").path("totalItems").asInt()).isEqualTo(5);
        Map<String, Integer> counts = new java.util.HashMap<>();
        list.path("items").forEach(s -> counts.put(s.path("code").asString(), s.path("memberCount").asInt()));
        assertThat(counts).containsEntry("SEG-DIGITAL", 3).containsEntry("SEG-NOT-EBILL", 7).containsEntry("SEG-AT-RISK", 1)
                .containsEntry("SEG-TORINO", 1).containsEntry("SEG-VIP-EVENT", 2);

        assertThat(ids(get("/v1/segments/SEG-DIGITAL/members").path("items")))
                .containsExactly("MBR-000004", "MBR-000005", "MBR-000011");
        assertThat(ids(get("/v1/segments/SEG-AT-RISK/members").path("items"))).containsExactly("MBR-000006");
        JsonNode davide = get("/v1/members/MBR-000004/segments");
        List<String> codes = new ArrayList<>();
        davide.forEach(s -> codes.add(s.path("code").asString()));
        assertThat(codes).containsExactly("SEG-DIGITAL", "SEG-TORINO", "SEG-VIP-EVENT");
        assertThat(get("/v1/members?segment=SEG-AT-RISK").path("items").get(0).path("id").asString()).isEqualTo("MBR-000006");
        JsonNode digital = get("/v1/segments/SEG-DIGITAL");
        assertThat(digital.path("type").asString()).isEqualTo("DYNAMIC");
        assertThat(digital.path("refreshedAt").isMissingNode()).isFalse();

        // Dopo il seed ogni appartenenza è annunciata: gli snapshot degli altri servizi la conoscono (Q-81).
        List<JsonNode> entered = facts(r -> ENTERED.equals(r.path("type").asString())
                && "SEG-DIGITAL".equals(r.path("data").path("segmentCode").asString()), 3);
        assertThat(entered).extracting(f -> f.path("subject").asString())
                .contains("member:MBR-000004", "member:MBR-000005", "member:MBR-000011");
    }

    @Test
    @Order(2)
    void previewTierGoldPlatinumReturnsFourWithoutSaving() {
        JsonNode preview = send(HttpMethod.POST, "/v1/segments/preview", null, Map.of("criteria",
                Map.of("op", "all", "rules", List.of(Map.of("field", "member.tier", "cmp", "in", "value", List.of("GOLD", "PLATINUM"))))), 200);
        assertThat(preview.path("count").asInt()).isEqualTo(4);
        assertThat(ids(preview.path("sample"))).containsExactly("MBR-000004", "MBR-000005", "MBR-000006", "MBR-000011");
        assertThat(preview.path("sample").get(0).path("name").asString()).isEqualTo("Davide Russo");

        // Finestre mobili e stato: senza il filtro ACTIVE anche Roberto (bloccato, fermo da 100 giorni) è "a rischio".
        assertThat(count(Map.of("field", "member.lastActivityDaysAgo", "cmp", "gt", "value", 45))).isEqualTo(2);
        assertThat(count(Map.of("field", "member.actions.purchase.completed.count30d", "cmp", "gte", "value", 2))).isEqualTo(4);
        assertThat(count(Map.of("field", "member.purchases.amount90d", "cmp", "gt", "value", 300))).isEqualTo(1);
        assertThat(count(Map.of("field", "member.balance.PTS", "cmp", "gte", "value", 10000))).isEqualTo(2);

        RestClientResponseException e = error(HttpMethod.POST, "/v1/segments/preview", null,
                Map.of("criteria", Map.of("op", "all", "rules", List.of(Map.of("field", "data.amount", "cmp", "gte", "value", 1)))));
        assertThat(e.getStatusCode().value()).isEqualTo(422);
        JsonNode problem = e.getResponseBodyAs(JsonNode.class);
        assertThat(problem.path("code").asString()).isEqualTo("INVALID_CRITERIA");
        assertThat(problem.path("errors").get(0).path("field").asString()).isEqualTo("criteria.rules[0].field");
        assertThat(get("/v1/segments").path("page").path("totalItems").asInt()).as("l'anteprima non salva").isEqualTo(5);
    }

    @Test
    @Order(3)
    void writesNeedSegmentWrite() {
        Map<String, Object> body = Map.of("code", "SEG-NOPE", "name", "No", "type", "DYNAMIC",
                "criteria", Map.of("field", "member.tier", "cmp", "eq", "value", "GOLD"));
        assertThat(error(HttpMethod.POST, "/v1/segments", "ANALYST:sara.analyst", body).getStatusCode().value()).isEqualTo(403);
        assertThat(error(HttpMethod.POST, "/v1/segments", "CARE:paolo.care", body).getStatusCode().value()).isEqualTo(403);
        assertThat(error(HttpMethod.POST, "/v1/segments/SEG-DIGITAL/refresh", "LEGAL:elena.legal", null).getStatusCode().value())
                .isEqualTo(403);
        assertThat(error(HttpMethod.POST, "/v1/demo/jobs/refresh-segments", MARKETING, null).getStatusCode().value())
                .as("il job demo è solo ADMIN").isEqualTo(403);
        assertThat(error(HttpMethod.POST, "/v1/segments", MARKETING, Map.of("code", "SEG-DIGITAL", "name", "Doppio", "type", "DYNAMIC",
                "criteria", Map.of("field", "member.tier", "cmp", "eq", "value", "GOLD"))).getStatusCode().value()).isEqualTo(409);
        assertThat(error(HttpMethod.POST, "/v1/segments", MARKETING, Map.of("code", "seg bad", "name", "X", "type", "DYNAMIC",
                "criteria", Map.of("field", "member.tier", "cmp", "eq", "value", "GOLD"))).getStatusCode().value()).isEqualTo(422);
    }

    @Test
    @Order(4)
    void dynamicLifecycleEmitsOnlyDifferences() {
        JsonNode created = send(HttpMethod.POST, "/v1/segments", MARKETING, Map.of("code", "SEG-SILVER", "name", "Argento",
                "type", "DYNAMIC", "criteria", Map.of("op", "all", "rules", List.of(Map.of("field", "member.tier", "cmp", "eq", "value", "SILVER")))), 201);
        assertThat(created.path("memberCount").asInt()).isEqualTo(4); // Marco, Giulia, Chiara, Matteo
        assertThat(created.path("createdBy").asString()).isEqualTo(MARKETING);
        List<JsonNode> entered = facts(r -> ENTERED.equals(r.path("type").asString())
                && "SEG-SILVER".equals(r.path("data").path("segmentCode").asString()), 4);
        assertThat(entered).hasSize(4);
        String envelope = resource("contracts/events/envelope.schema.json");
        String enteredSchema = resource("contracts/events/fact/member.segment.entered.schema.json");
        for (JsonNode f : entered) {
            assertThat(validator.validate("it-env", envelope, f.toString())).isEmpty();
            assertThat(validator.validate("it-entered", enteredSchema, f.path("data").toString())).isEmpty();
            assertThat(f.path("lhactor").asString()).isEqualTo(MARKETING);
            assertThat(f.path("source").asString()).isEqualTo("urn:loyaltyhub:service:member");
        }

        int version = created.path("version").asInt();
        JsonNode updated = send(HttpMethod.PUT, "/v1/segments/SEG-SILVER", MARKETING, Map.of("version", version,
                "criteria", Map.of("op", "all", "rules", List.of(
                        Map.of("field", "member.tier", "cmp", "eq", "value", "SILVER"),
                        Map.of("field", "member.city", "cmp", "eq", "value", "Milano")))), 200);
        assertThat(updated.path("memberCount").asInt()).isEqualTo(1);
        List<JsonNode> left = facts(r -> LEFT.equals(r.path("type").asString())
                && "SEG-SILVER".equals(r.path("data").path("segmentCode").asString()), 3);
        assertThat(left).extracting(f -> f.path("subject").asString())
                .containsExactlyInAnyOrder("member:MBR-000003", "member:MBR-000007", "member:MBR-000010");
        String leftSchema = resource("contracts/events/fact/member.segment.left.schema.json");
        assertThat(validator.validate("it-left", leftSchema, left.get(0).path("data").toString())).isEmpty();

        JsonNode refresh = send(HttpMethod.POST, "/v1/segments/SEG-SILVER/refresh", MARKETING, null, 200);
        assertThat(refresh.path("entered").asInt()).isZero();
        assertThat(refresh.path("left").asInt()).isZero();
        assertThat(refresh.path("total").asInt()).isEqualTo(1);

        assertThat(error(HttpMethod.PUT, "/v1/segments/SEG-SILVER", MARKETING, Map.of("version", version, "name", "Vecchia"))
                .getStatusCode().value()).isEqualTo(409);
        assertThat(error(HttpMethod.PUT, "/v1/segments/SEG-SILVER", MARKETING, Map.of("type", "STATIC"))
                .getStatusCode().value()).isEqualTo(409);

        JsonNode archived = send(HttpMethod.PUT, "/v1/segments/SEG-SILVER", MARKETING, Map.of("status", "ARCHIVED"), 200);
        assertThat(archived.path("status").asString()).isEqualTo("ARCHIVED");
        assertThat(archived.path("memberCount").asInt()).isZero();
        assertThat(facts(r -> LEFT.equals(r.path("type").asString()) && "member:MBR-000002".equals(r.path("subject").asString())
                && "SEG-SILVER".equals(r.path("data").path("segmentCode").asString()), 1)).hasSize(1);
        assertThat(error(HttpMethod.POST, "/v1/segments/SEG-SILVER/refresh", MARKETING, null).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    @Order(5)
    void staticSegmentListIsReplaced() {
        JsonNode out = send(HttpMethod.PUT, "/v1/segments/SEG-VIP-EVENT/members", MARKETING,
                Map.of("memberIds", List.of("MBR-000005", "MBR-000011")), 200);
        assertThat(out.path("entered").asInt()).isEqualTo(1);
        assertThat(out.path("left").asInt()).isEqualTo(1);
        assertThat(out.path("total").asInt()).isEqualTo(2);
        assertThat(ids(get("/v1/segments/SEG-VIP-EVENT/members").path("items"))).containsExactly("MBR-000005", "MBR-000011");

        RestClientResponseException unknown = error(HttpMethod.PUT, "/v1/segments/SEG-VIP-EVENT/members", MARKETING,
                Map.of("memberIds", List.of("MBR-999999")));
        assertThat(unknown.getStatusCode().value()).isEqualTo(422);
        assertThat(unknown.getResponseBodyAs(JsonNode.class).path("code").asString()).isEqualTo("MEMBER_NOT_FOUND");
        assertThat(error(HttpMethod.PUT, "/v1/segments/SEG-DIGITAL/members", MARKETING, Map.of("memberIds", List.of()))
                .getStatusCode().value()).isEqualTo(409);

        JsonNode created = send(HttpMethod.POST, "/v1/segments", MARKETING, Map.of("code", "SEG-PANEL", "name", "Panel",
                "type", "STATIC", "memberIds", List.of("MBR-000001", "MBR-000009")), 201);
        assertThat(created.path("memberCount").asInt()).isEqualTo(2);
    }

    @Test
    @Order(6)
    void scnDigitalLabelsMarcoAndTheRecomputeMovesHimIntoSegDigital() {
        publishAction("ebill.activated", "MBR-000002", Map.of("contractId", "CTR-IT-1"));
        publishAction("directdebit.activated", "MBR-000002", Map.of("contractId", "CTR-IT-1"));
        long deadline = System.currentTimeMillis() + 15_000;
        List<String> labels = List.of();
        while (System.currentTimeMillis() < deadline && labels.size() < 2) {
            labels = new ArrayList<>();
            for (JsonNode l : get("/v1/members/MBR-000002").path("labels")) {
                labels.add(l.asString());
            }
            sleep();
        }
        assertThat(labels).containsExactly("ebill", "directdebit");
        List<JsonNode> updated = facts(r -> "io.loyaltyhub.fact.member.updated".equals(r.path("type").asString())
                && "member:MBR-000002".equals(r.path("subject").asString()), 2);
        assertThat(updated).hasSize(2);
        assertThat(updated.get(1).path("data").path("labels").toString()).contains("ebill", "directdebit");
        assertThat(updated.get(0).path("lhcausationid").asString()).as("nello stesso tracciato dell'azione").isNotBlank();

        // Prima del ricalcolo nulla cambia (docs §5: su richiesta, dopo il reset, ogni 15 minuti).
        assertThat(get("/v1/segments/SEG-DIGITAL").path("memberCount").asInt()).isEqualTo(3);
        JsonNode job = send(HttpMethod.POST, "/v1/demo/jobs/refresh-segments", "ADMIN:marta.admin", null, 200);
        assertThat(job.path("entered").asInt()).isEqualTo(1);
        assertThat(job.path("left").asInt()).isEqualTo(1);
        assertThat(ids(get("/v1/segments/SEG-DIGITAL/members").path("items"))).contains("MBR-000002");
        assertThat(facts(r -> ENTERED.equals(r.path("type").asString()) && "member:MBR-000002".equals(r.path("subject").asString())
                && "SEG-DIGITAL".equals(r.path("data").path("segmentCode").asString()), 1)).hasSize(1);
        assertThat(facts(r -> LEFT.equals(r.path("type").asString()) && "member:MBR-000002".equals(r.path("subject").asString())
                && "SEG-NOT-EBILL".equals(r.path("data").path("segmentCode").asString()), 1)).hasSize(1);

        JsonNode again = send(HttpMethod.POST, "/v1/demo/jobs/refresh-segments", "ADMIN:marta.admin", null, 200);
        assertThat(again.path("entered").asInt()).as("solo differenze").isZero();
        assertThat(again.path("left").asInt()).isZero();
        // Macchina del tempo: fra 60 giorni anche Marco (attivo oggi) supera i 45 giorni senza attività.
        JsonNode future = send(HttpMethod.POST, "/v1/demo/jobs/refresh-segments?asOf=" + java.time.LocalDate.now().plusDays(60),
                "ADMIN:marta.admin", null, 200);
        assertThat(future.path("entered").asInt()).isPositive();
        assertThat(ids(get("/v1/segments/SEG-AT-RISK/members").path("items"))).contains("MBR-000002", "MBR-000006");
    }

    @Test
    @Order(7)
    void projectionKeepsPtsAndStsApart() {
        long before = get("/v1/members/MBR-000011").path("balancePts").asLong();
        publishFact("io.loyaltyhub.fact.wallet.points.earned", "MBR-000011",
                Map.of("currency", "STS", "amount", 60, "balanceAfter", 3410));
        long deadline = System.currentTimeMillis() + 15_000;
        long sts = -1;
        while (System.currentTimeMillis() < deadline && sts != 3410) {
            sts = get("/v1/members/MBR-000011").path("periodSts").asLong();
            sleep();
        }
        assertThat(sts).isEqualTo(3410);
        assertThat(get("/v1/members/MBR-000011").path("balancePts").asLong()).as("gli STS non toccano il saldo PTS").isEqualTo(before);
    }

    @Test
    @Order(99)
    void resetRestoresSeedMembershipsAndAnnouncesWhatChanged() {
        send(HttpMethod.POST, "/v1/demo/reset", "ADMIN:marta.admin", null, 200);
        JsonNode list = get("/v1/segments");
        assertThat(list.path("page").path("totalItems").asInt()).isEqualTo(5);
        assertThat(ids(get("/v1/segments/SEG-DIGITAL/members").path("items"))).containsExactly("MBR-000004", "MBR-000005", "MBR-000011");
        // Marco non è più digitale (etichette del seed): `left` per SEG-DIGITAL; SEG-PANEL (non nei seed) sparisce.
        assertThat(facts(r -> LEFT.equals(r.path("type").asString()) && "member:MBR-000002".equals(r.path("subject").asString())
                && "SEG-DIGITAL".equals(r.path("data").path("segmentCode").asString()), 1)).hasSize(1);
        assertThat(facts(r -> LEFT.equals(r.path("type").asString())
                && "SEG-PANEL".equals(r.path("data").path("segmentCode").asString()), 2)).hasSize(2);
        assertThat(get("/v1/members/MBR-000002").path("labels").size()).isZero();
    }

    // ---------- helper ----------

    private long count(Map<String, Object> criteria) {
        return send(HttpMethod.POST, "/v1/segments/preview", null, Map.of("criteria", criteria), 200).path("count").asLong();
    }

    private static List<String> ids(JsonNode items) {
        List<String> out = new ArrayList<>();
        items.forEach(i -> out.add(i.path("memberId").asString()));
        return out;
    }

    private JsonNode get(String path) {
        return client().get().uri(path).retrieve().body(JsonNode.class);
    }

    private JsonNode send(HttpMethod method, String path, String actor, Object body, int expected) {
        RestClient.RequestBodySpec req = client().method(method).uri(path).contentType(MediaType.APPLICATION_JSON);
        if (actor != null) {
            req.header("X-LH-Actor", actor);
        }
        if (body != null) {
            req.body(body);
        }
        var res = req.retrieve().toEntity(JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(expected);
        return res.getBody();
    }

    private RestClientResponseException error(HttpMethod method, String path, String actor, Object body) {
        try {
            send(method, path, actor, body, -1);
            fail("atteso un errore");
            return null;
        } catch (RestClientResponseException e) {
            return e;
        }
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private static String resource(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Fatti che soddisfano {@code match}: legge dall'inizio finché ne trova {@code expected} (o 15 s). */
    private List<JsonNode> facts(Predicate<JsonNode> match, int expected) {
        List<JsonNode> out = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, System.getProperty("spring.embedded.kafka.brokers"),
                ConsumerConfig.GROUP_ID_CONFIG, "seg-it-" + System.nanoTime(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(FACTS));
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline && out.size() < expected) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(400))) {
                    JsonNode e = mapper.readTree(r.value());
                    if (match.test(e)) {
                        out.add(e);
                    }
                }
            }
            // un giro in più: scova eventuali fatti oltre l'atteso
            for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(800))) {
                JsonNode e = mapper.readTree(r.value());
                if (match.test(e)) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    private void publishAction(String shortType, String memberId, Map<String, Object> data) {
        String id = "seg-it-" + System.nanoTime();
        publish(ACTIONS, memberId, Map.of("specversion", "1.0", "id", id, "source", "urn:loyaltyhub:source:billing",
                "type", "io.loyaltyhub.action." + shortType, "subject", "member:" + memberId, "time", Instant.now().toString(),
                "lhcorrelationid", id, "lhhop", 0, "data", data));
    }

    private void publishFact(String type, String memberId, Map<String, Object> data) {
        String id = "seg-it-" + System.nanoTime();
        publish(FACTS, memberId, Map.of("specversion", "1.0", "id", id, "source", "urn:loyaltyhub:service:wallet",
                "type", type, "subject", "member:" + memberId, "time", Instant.now().toString(),
                "lhcorrelationid", id, "lhhop", 0, "data", data));
    }

    private void publish(String topic, String key, Map<String, Object> event) {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class))) {
            producer.send(new ProducerRecord<>(topic, key, mapper.writeValueAsString(event))).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
