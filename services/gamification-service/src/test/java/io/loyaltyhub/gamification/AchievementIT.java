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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AchievementIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String ACTION = "io.loyaltyhub.action.";

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

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
    void seededProgressAndBadges() {
        JsonNode matteo = achievement("MBR-000010", "ACH-STREAK-7");
        assertThat(matteo.path("value").asLong()).isEqualTo(5);
        assertThat(matteo.path("target").asLong()).isEqualTo(7);
        assertThat(achievement("MBR-000003", "ACH-3-PURCHASES-MONTH").path("value").asLong()).isEqualTo(2);

        JsonNode francesca = get("/v1/portal/badges?memberId=MBR-000005");
        assertThat(francesca.size()).isEqualTo(6);
        francesca.forEach(b -> assertThat(b.path("awardedAt").isNull() || b.path("awardedAt").isMissingNode()).isFalse());
        JsonNode anna = get("/v1/portal/badges?memberId=MBR-000001");
        anna.forEach(b -> assertThat(b.path("awardedAt").isNull() || b.path("awardedAt").isMissingNode()).isTrue());
        assertThat(anna.get(0).path("unlockHint").asString()).startsWith("Completa «");

        JsonNode first = byCode(get("/v1/achievements"), "ACH-FIRST-PURCHASE");
        assertThat(first.path("completions").asLong()).isEqualTo(8);
        assertThat(first.path("metric").asString()).isEqualTo("COUNT");
        assertThat(byCode(get("/v1/badges"), "BDG-FIRST").path("holders").asLong()).isEqualTo(8);
    }

    @Test
    void threePurchasesInAMonthCompleteOnceAndTheFourthDoesNotReemit() throws Exception {
        for (int i = 1; i <= 4; i++) {
            publishAction("MBR-000007", "purchase.completed", Map.of("orderId", "ORD-T-" + i, "amount", 40));
        }
        List<JsonNode> completed = facts("member:MBR-000007", "io.loyaltyhub.fact.achievement.completed",
                e -> e.path("data").path("achievementCode").asString().equals("ACH-3-PURCHASES-MONTH"));
        assertThat(completed).as("completato una volta sola").hasSize(1);
        List<JsonNode> progressed = facts("member:MBR-000007", "io.loyaltyhub.fact.achievement.progressed",
                e -> e.path("data").path("achievementCode").asString().equals("ACH-3-PURCHASES-MONTH"));
        assertThat(progressed).extracting(e -> e.path("data").path("value").asLong()).containsExactly(1L, 2L, 3L);
        List<JsonNode> badge = facts("member:MBR-000007", "io.loyaltyhub.fact.badge.awarded", e -> true);
        assertThat(badge).hasSize(1);
        assertThat(badge.get(0).path("data").path("badgeCode").asString()).isEqualTo("BDG-TRIS");
        assertThat(badge.get(0).path("data").path("origin").asString()).isEqualTo("ACHIEVEMENT");
        assertThat(achievement("MBR-000007", "ACH-BIG-SPENDER").path("value").asLong()).isEqualTo(160);
    }

    @Test
    void digitalNeedsBothTypesAndAwardsItsBadge() throws Exception {
        publishAction("MBR-000002", "ebill.activated", Map.of("contractId", "CTR-1"));
        publishAction("MBR-000002", "ebill.activated", Map.of("contractId", "CTR-2"));
        publishAction("MBR-000002", "directdebit.activated", Map.of("contractId", "CTR-1"));
        List<JsonNode> done = facts("member:MBR-000002", "io.loyaltyhub.fact.achievement.completed",
                e -> e.path("data").path("achievementCode").asString().equals("ACH-DIGITAL"));
        assertThat(done).hasSize(1);
        assertThat(facts("member:MBR-000002", "io.loyaltyhub.fact.badge.awarded", e -> true))
                .extracting(e -> e.path("data").path("badgeCode").asString()).containsExactly("BDG-DIGITAL");
        JsonNode digital = achievement("MBR-000002", "ACH-DIGITAL");
        assertThat(digital.path("completedAt").isNull()).isFalse();
        assertThat(digital.path("badge").path("name").asString()).isEqualTo("Zero carta");
    }

    @Test
    void streakGrowsOncePerDay() throws Exception {
        publishAction("MBR-000010", "app.login.daily", Map.of("platform", "IOS"));
        publishAction("MBR-000010", "app.login.daily", Map.of("platform", "IOS"));
        List<JsonNode> progressed = facts("member:MBR-000010", "io.loyaltyhub.fact.achievement.progressed",
                e -> e.path("data").path("achievementCode").asString().equals("ACH-STREAK-7"));
        assertThat(progressed).extracting(e -> e.path("data").path("value").asLong()).containsExactly(6L);
    }

    @Test
    void badgeEffectIsIdempotentAndBlockedMembersAreIgnored() throws Exception {
        publishEffect("MBR-000009", "BDG-READER", "EFF-BADGE-1");
        publishEffect("MBR-000009", "BDG-READER", "EFF-BADGE-1");
        List<JsonNode> awarded = facts("member:MBR-000009", "io.loyaltyhub.fact.badge.awarded", e -> true);
        assertThat(awarded).hasSize(1);
        assertThat(awarded.get(0).path("data").path("origin").asString()).isEqualTo("CAMPAIGN");

        publishAction("MBR-000008", "purchase.completed", Map.of("orderId", "ORD-BLK", "amount", 10));
        assertThat(facts("member:MBR-000008", "io.loyaltyhub.fact.achievement.progressed", e -> true))
                .as("Roberto è BLOCKED").isEmpty();
    }

    @Test
    void managementNeedsObjectEdit() {
        Map<String, Object> body = Map.of("code", "ACH-IT-QUIZ", "name", "Quiz", "actionTypes", List.of("quiz.completed"),
                "metric", "COUNT", "target", 2, "period", "MONTH", "repeatable", true, "badgeCode", "BDG-FIRST");
        assertThat(status("POST", "/v1/achievements", "ANALYST:sara", body)).isEqualTo(403);
        JsonNode created = send("POST", "/v1/achievements", "MARKETING:luca", body, 201);
        assertThat(created.path("code").asString()).isEqualTo("ACH-IT-QUIZ");
        assertThat(send("POST", "/v1/achievements", "MARKETING:luca", Map.of("code", "ACH-IT-SUM", "name", "Somma",
                "actionTypes", List.of("purchase.completed"), "metric", "SUM", "target", 100, "period", "EVER"), 422)
                .path("code").asString()).isEqualTo("ACHIEVEMENT_INVALID");
        assertThat(send("PUT", "/v1/achievements/ACH-IT-QUIZ", "MARKETING:luca", Map.of("target", 3), 200)
                .path("target").asLong()).isEqualTo(3);
        assertThat(send("POST", "/v1/badges", "MARKETING:luca", Map.of("code", "BDG-IT", "name", "Prova", "icon", "star"), 201)
                .path("code").asString()).isEqualTo("BDG-IT");
    }

    // ---------- helper ----------

    private JsonNode achievement(String memberId, String code) {
        return byCode(get("/v1/portal/achievements?memberId=" + memberId), code);
    }

    private static JsonNode byCode(JsonNode list, String code) {
        for (JsonNode n : list) {
            if (code.equals(n.path("code").asString())) {
                return n;
            }
        }
        throw new AssertionError("assente: " + code);
    }

    private void publishAction(String memberId, String shortType, Map<String, Object> data) throws Exception {
        String id = "ACT-" + UUID.randomUUID();
        publish("lh.actions.v1", memberId, Map.of("specversion", "1.0", "id", id, "source", "urn:loyaltyhub:source:ecommerce",
                "type", ACTION + shortType, "subject", "member:" + memberId, "time", Instant.now().toString(),
                "lhcorrelationid", id, "lhhop", 0, "data", data));
    }

    private void publishEffect(String memberId, String badgeCode, String effectId) throws Exception {
        String id = "EFF-" + UUID.randomUUID();
        publish("lh.effects.v1", memberId, Map.of("specversion", "1.0", "id", id, "source", "urn:loyaltyhub:service:campaign",
                "type", "io.loyaltyhub.effect.badge.award", "subject", "member:" + memberId, "time", Instant.now().toString(),
                "lhcorrelationid", id, "lhhop", 1, "data", Map.of("effectId", effectId, "campaignCode", "CMP-IT-BADGE",
                        "actionId", "A-" + id, "actionType", "quiz.completed", "badgeCode", badgeCode)));
    }

    private void publish(String topic, String key, Map<String, Object> event) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class))) {
            producer.send(new ProducerRecord<>(topic, key, mapper.writeValueAsString(event))).get();
        }
    }

    /** Fatti del soggetto e tipo indicati; attende finché per 3 s non ne arrivano di nuovi (max 15 s). */
    private List<JsonNode> facts(String subject, String type, Predicate<JsonNode> filter) {
        List<JsonNode> out = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "group.id", "ach-it-" + UUID.randomUUID(), "auto.offset.reset", "earliest",
                "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class))) {
            consumer.subscribe(List.of("lh.facts.v1"));
            long deadline = System.currentTimeMillis() + 15_000;
            long quietUntil = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline && System.currentTimeMillis() < quietUntil) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(400))) {
                    JsonNode e = mapper.readTree(r.value());
                    if (subject.equals(e.path("subject").asString()) && type.equals(e.path("type").asString()) && filter.test(e)) {
                        out.add(e);
                        quietUntil = System.currentTimeMillis() + 3_000;
                    }
                }
            }
        }
        return out;
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
