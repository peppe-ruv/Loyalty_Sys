package io.loyaltyhub.gamification;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import io.loyaltyhub.gamification.infra.MemberSnapshotRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5.4 obiettivi e badge (docs/03 §8, docs/servizi/gamification-service.md §5, §7; F-ACH-01..03): seed dei progressi,
 * 3 acquisti nel mese → completato una volta (il 4° non riemette), DISTINCT_TYPES con badge, serie di giorni,
 * effetto {@code badge.award} idempotente, membro bloccato ignorato, gestione con ruoli. EmbeddedKafka + Zonky.
 */
/**
 * M5.5 classifiche (docs/03 §8, docs/servizi/gamification-service.md §5; F-LDB-01): seed coerente coi saldi, membri non
 * attivi fuori dal ranking, punteggi da {@code wallet.points.earned} (PTS/STS), parimerito a chi arriva prima,
 * {@code ACTION_COUNT} dalle azioni elencate, portale solo con nickname. EmbeddedKafka + Zonky.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderboardIT {

    private static final EmbeddedPostgres PG = startPg();

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private MemberSnapshotRepository snapshots;

    @Autowired
    private JdbcClient jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=gamification");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    void seededRankingExcludesInactiveMembersAndShowsOnlyNicknames() {
        JsonNode month = get("/v1/portal/leaderboards/LDB-MONTH-PTS?memberId=MBR-000002");
        assertThat(month.toString()).as("il portale non espone identificativi").doesNotContain("MBR-");
        assertThat(month.path("top").get(0).path("nickname").asString()).isEqualTo("fra_r");
        assertThat(month.path("top").get(0).path("score").asLong()).isEqualTo(1840);
        assertThat(month.path("me").path("rank").asInt()).isEqualTo(6);
        assertThat(month.path("top").get(5).path("isMe").asBoolean()).isTrue();
        assertThat(month.toString()).as("Roberto è BLOCKED").doesNotContain("rob_c");
        assertThat(month.path("participants").asInt()).isEqualTo(9);

        JsonNode sts = get("/v1/portal/leaderboards/LDB-EDITION-STS?memberId=MBR-000001");
        assertThat(sts.path("top").get(0).path("score").asLong()).as("STS dell'edizione = saldo del wallet").isEqualTo(9600);
        assertThat(sts.path("me").isNull() || sts.path("me").isMissingNode()).as("Anna non ha STS").isTrue();

        JsonNode bo = get("/v1/leaderboards/LDB-MONTH-PTS/ranking");
        // Verifica la parameterizzazione del parametro limit, e l'assenza di limit
        JsonNode boLimit = get("/v1/leaderboards/LDB-MONTH-PTS/ranking?limit=1");
        assertThat(boLimit.path("items").size()).isEqualTo(1);
        assertThat(boLimit.path("items").get(0).path("memberId").asString()).isEqualTo("MBR-000005");
        assertThat(bo.path("items").size()).isEqualTo(9);
        assertThat(bo.path("items").get(0).path("memberId").asString()).isEqualTo("MBR-000005");
        assertThat(bo.path("periods").get(0).asString()).isEqualTo(bo.path("currentPeriodKey").asString());
    }

    @Test
    void pointsEarnedFeedTheRightBoardAndTiesGoToWhoArrivedFirst() throws Exception {
        publishEarned("MBR-000009", "PTS", 500);
        publishEarned("MBR-000009", "STS", 200);
        // Anna 100 + 250 = 350 come Chiara (seed): Chiara ci è arrivata prima e resta davanti.
        publishEarned("MBR-000001", "PTS", 250);
        JsonNode elisa = await("/v1/portal/leaderboards/LDB-MONTH-PTS?memberId=MBR-000009", n -> n.path("me").path("score").asLong() == 500);
        assertThat(elisa.path("me").path("rank").asInt()).isEqualTo(6);
        JsonNode anna = await("/v1/portal/leaderboards/LDB-MONTH-PTS?memberId=MBR-000001", n -> n.path("me").path("score").asLong() == 350);
        JsonNode chiara = get("/v1/portal/leaderboards/LDB-MONTH-PTS?memberId=MBR-000007");
        assertThat(chiara.path("me").path("rank").asInt()).isLessThan(anna.path("me").path("rank").asInt());
        JsonNode sts = await("/v1/portal/leaderboards/LDB-EDITION-STS?memberId=MBR-000009", n -> n.path("me").path("score").asLong() == 200);
        assertThat(sts.path("me").path("rank").asInt()).isGreaterThan(1);
    }

    @Test
    void actionCountBoardsAndManagementRules() throws Exception {
        Map<String, Object> body = Map.of("code", "LDB-IT-QUIZ", "name", "Quiz del mese", "metric", "ACTION_COUNT",
                "actionTypes", List.of("quiz.completed"), "period", "MONTH", "topN", 5);
        assertThat(status("POST", "/v1/leaderboards", "ANALYST:sara", body)).isEqualTo(403);
        send("POST", "/v1/leaderboards", "MARKETING:luca", body, 201);
        assertThat(send("PUT", "/v1/leaderboards/LDB-IT-QUIZ", "MARKETING:luca", Map.of("metric", "PTS_EARNED"), 409)
                .path("code").asString()).isEqualTo("LEADERBOARD_LOCKED");
        assertThat(send("POST", "/v1/leaderboards", "MARKETING:luca", Map.of("code", "LDB-IT-BAD", "name", "x",
                "metric", "ACTION_COUNT", "period", "MONTH"), 422).path("code").asString()).isEqualTo("LEADERBOARD_INVALID");

        publishAction("MBR-000003", "quiz.completed");
        publishAction("MBR-000003", "quiz.completed");
        publishAction("MBR-000003", "survey.completed");
        JsonNode quiz = await("/v1/portal/leaderboards/LDB-IT-QUIZ?memberId=MBR-000003", n -> n.path("me").path("score").asLong() == 2);
        assertThat(quiz.path("me").path("rank").asInt()).isEqualTo(1);
    }

    /**
     * Q-368 (ADR-032, F2-EVT-02): con {@code resolve=ids} le voci portano il {@code memberId} e non il soprannome dello
     * snapshot (lo inserisce il BFF); {@code isMe} resta calcolato; un valore diverso da {@code ids} è un 400.
     */
    @Test
    void resolveIdsReturnsMemberIdsInsteadOfNicknames() {
        JsonNode plain = get("/v1/portal/leaderboards/LDB-MONTH-PTS?memberId=MBR-000002");
        JsonNode ids = get("/v1/portal/leaderboards/LDB-MONTH-PTS?memberId=MBR-000002&resolve=ids");
        assertThat(ids.path("top").size()).isEqualTo(plain.path("top").size());
        for (int i = 0; i < ids.path("top").size(); i++) {
            JsonNode e = ids.path("top").get(i);
            assertThat(e.path("memberId").asString()).startsWith("MBR-");
            assertThat(e.hasNonNull("nickname")).as("niente soprannome dello snapshot").isFalse();
            assertThat(e.path("rank").asInt()).isEqualTo(plain.path("top").get(i).path("rank").asInt());
            assertThat(e.path("score").asLong()).isEqualTo(plain.path("top").get(i).path("score").asLong());
            assertThat(e.path("isMe").asBoolean()).isEqualTo("MBR-000002".equals(e.path("memberId").asString()));
        }
        assertThat(ids.path("me").path("rank").asInt()).isEqualTo(plain.path("me").path("rank").asInt());
        assertThat(get("/v1/portal/leaderboards?memberId=MBR-000002&resolve=ids").get(0).path("top").get(0).has("memberId")).isTrue();
        assertThat(plain.toString()).as("la risposta di sempre non cambia").doesNotContain("MBR-");

        JsonNode bo = get("/v1/leaderboards/LDB-MONTH-PTS/ranking?resolve=ids");
        assertThat(bo.path("items").get(0).path("memberId").asString()).isEqualTo("MBR-000005");
        assertThat(bo.path("items").get(0).hasNonNull("nickname")).isFalse();

        assertThat(status("GET", "/v1/portal/leaderboards/LDB-MONTH-PTS?memberId=MBR-000002&resolve=nicknames", "ANALYST:sara", null))
                .isEqualTo(400);
    }

    /**
     * Doppia lettura {@code member.*:1}/{@code :2} (ADR-032, Q-346): {@code :1} porta il soprannome; {@code :2} solo lo
     * stato, e il soprannome salvato resta (anche se un {@code :2} ne portasse uno); un campo assente non sovrascrive.
     */
    @Test
    void memberFactsV2NeverOverwriteTheStoredNickname() throws Exception {
        String m = "MBR-900001";
        String v1 = publishMember("registered", m, 1, Map.of("memberId", m, "nickname", "nuovo_it", "status", "ACTIVE"));
        awaitProcessed(v1);
        assertThat(snapshots.find(m).orElseThrow().nickname()).isEqualTo("nuovo_it");

        String v2 = publishMember("updated", m, 2, Map.of("memberId", m, "status", "BLOCKED", "nickname", "intruso",
                "province", "MI", "birthYear", 1990));
        awaitProcessed(v2);
        MemberSnapshotRepository.Snapshot blocked = snapshots.find(m).orElseThrow();
        assertThat(blocked.status()).isEqualTo("BLOCKED");
        assertThat(blocked.nickname()).as(":2 non legge il soprannome").isEqualTo("nuovo_it");

        String noStatus = publishMember("updated", m, 2, Map.of("memberId", m, "labels", List.of("vip")));
        awaitProcessed(noStatus);
        MemberSnapshotRepository.Snapshot after = snapshots.find(m).orElseThrow();
        assertThat(after.status()).as("stato assente: resta quello salvato").isEqualTo("BLOCKED");
        assertThat(after.nickname()).isEqualTo("nuovo_it");

        String fresh = publishMember("registered", "MBR-900002", 2, Map.of("memberId", "MBR-900002", "status", "ACTIVE"));
        awaitProcessed(fresh);
        MemberSnapshotRepository.Snapshot created = snapshots.find("MBR-900002").orElseThrow();
        assertThat(created.status()).isEqualTo("ACTIVE");
        assertThat(created.nickname()).as("membro nuovo da :2: nessun soprannome, lo risolve il BFF").isNull();
    }

    // ---------- helper ----------

    private JsonNode await(String path, Predicate<JsonNode> done) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            last = get(path);
            if (done.test(last)) {
                return last;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("condizione non raggiunta su " + path + ": " + last);
    }

    private String publishMember(String name, String memberId, int version, Map<String, Object> data) throws Exception {
        String id = "FACT-" + UUID.randomUUID();
        publish("lh.facts.v1", memberId, Map.of("specversion", "1.0", "id", id, "source", "urn:loyaltyhub:service:member",
                "type", "io.loyaltyhub.fact.member." + name, "subject", "member:" + memberId, "time", Instant.now().toString(),
                "dataschema", "urn:loyaltyhub:schema:fact.member." + name + ":" + version,
                "lhcorrelationid", id, "lhhop", 0, "data", data));
        return id;
    }

    private void awaitProcessed(String eventId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            Long n = jdbc.sql("SELECT count(*) FROM processed_event WHERE event_id = ?").param(eventId).query(Long.class).single();
            if (n != null && n > 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("evento non elaborato: " + eventId);
    }

    private void publishEarned(String memberId, String currency, long amount) throws Exception {
        String id = "FACT-" + UUID.randomUUID();
        publish("lh.facts.v1", memberId, Map.of("specversion", "1.0", "id", id, "source", "urn:loyaltyhub:service:wallet",
                "type", "io.loyaltyhub.fact.wallet.points.earned", "subject", "member:" + memberId, "time", Instant.now().toString(),
                "lhcorrelationid", id, "lhhop", 2, "data", Map.of("ledgerEntryId", "L-" + id, "currency", currency,
                        "amount", amount, "balanceAfter", amount)));
    }

    private void publishAction(String memberId, String shortType) throws Exception {
        String id = "ACT-" + UUID.randomUUID();
        publish("lh.actions.v1", memberId, Map.of("specversion", "1.0", "id", id, "source", "urn:loyaltyhub:source:partner",
                "type", "io.loyaltyhub.action." + shortType, "subject", "member:" + memberId, "time", Instant.now().toString(),
                "lhcorrelationid", id, "lhhop", 0, "data", Map.of()));
    }

    private void publish(String topic, String key, Map<String, Object> event) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class))) {
            producer.send(new ProducerRecord<>(topic, key, mapper.writeValueAsString(event))).get();
        }
    }


    private JsonNode get(String path) {
        return RestClient.create("http://localhost:" + port).get().uri(path).retrieve().body(JsonNode.class);
    }

    private int status(String method, String path, String actor, Object body) {
        var spec = RestClient.create("http://localhost:" + port).method(HttpMethod.valueOf(method)).uri(path).header("X-LH-Actor", actor);
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return spec.exchange((req, res) -> res.getStatusCode().value());
    }

    private JsonNode send(String method, String path, String actor, Object body, int expected) {
        var spec = RestClient.create("http://localhost:" + port).method(HttpMethod.valueOf(method))
                .uri(path).header("X-LH-Actor", actor);
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return spec.exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(res.getStatusCode().value()).as(method + " " + path + " → " + text).isEqualTo(expected);
            return text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text);
        });
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
