package io.loyaltyhub.reward;

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
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * reward-service M4.1 (docs/servizi/reward-service.md §3, §7): catalogo seminato, fasce, ciclo di vita dei premi,
 * catalogo del portale con visibilità per tier/segmento e stato dello stock, snapshot aggiornato dai fatti.
 * Senza Docker: EmbeddedKafka + Zonky.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RewardServiceIT {

    private static final EmbeddedPostgres PG = startPg();

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=reward");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    void seededCatalogHasBandsAndRewards() {
        JsonNode bands = get("/v1/reward-bands");
        assertThat(bands.size()).isEqualTo(5);
        assertThat(bands.get(1).path("pointsThreshold").asLong()).isEqualTo(1500);
        assertThat(get("/v1/rewards").size()).isGreaterThanOrEqualTo(14);
        assertThat(get("/v1/reward-categories").size()).isEqualTo(5);
        assertThat(get("/v1/rewards?status=LIVE&band=F5").size()).isEqualTo(2);
    }

    @Test
    void portalCatalogAppliesTierSegmentStatusAndStockRules() {
        // Marco (MBR-000002) è SILVER.
        JsonNode catalog = get("/v1/portal/catalog?memberId=MBR-000002");
        JsonNode weekend = reward(catalog, "RWD-WEEKEND");
        assertThat(weekend.path("lockedByTier").path("requiredTiers").toString()).contains("GOLD", "PLATINUM");
        assertThat(weekend.path("pointsCost").asLong()).isEqualTo(12000);
        assertThat(reward(catalog, "RWD-EBIKE-RENT")).as("fuori segmento: escluso").isNull();
        assertThat(reward(catalog, "RWD-THERMOSTAT")).as("DRAFT: escluso").isNull();
        assertThat(reward(catalog, "RWD-GIFT-50")).as("IN_REVIEW: escluso").isNull();
        assertThat(reward(catalog, "RWD-POWERBANK").path("stockState").asString()).isEqualTo("SOLD_OUT");
        assertThat(reward(catalog, "RWD-SHOP-25").path("stockState").asString()).isEqualTo("LOW");
        assertThat(reward(catalog, "RWD-DONATION-TREE").path("stockState").asString()).isEqualTo("AVAILABLE");
        assertThat(reward(catalog, "RWD-SHOP-10").path("pointsCost").asLong()).isEqualTo(1500);

        // Davide (MBR-000004) è GOLD: il weekend non è bloccato; la serata PLATINUM sì.
        JsonNode davide = get("/v1/portal/catalog?memberId=MBR-000004");
        assertThat(reward(davide, "RWD-WEEKEND").path("lockedByTier").isMissingNode()).isTrue();
        assertThat(reward(davide, "RWD-PLATINUM-EVENT").path("lockedByTier").isMissingNode()).isFalse();

        JsonNode detail = get("/v1/portal/rewards/RWD-BILL-20?memberId=MBR-000002");
        assertThat(detail.path("terms").asString()).isNotBlank();
        assertThat(detail.path("perMemberLimit").asInt()).isEqualTo(2);
        assertThat(status("GET", "/v1/portal/rewards/RWD-THERMOSTAT", null, null)).isEqualTo(404);
    }

    @Test
    void tierFactUpdatesTheSnapshotAndUnlocksRewards() throws Exception {
        // MBR-000010 è SILVER: dopo tier.upgraded → GOLD il weekend si sblocca.
        assertThat(reward(get("/v1/portal/catalog?memberId=MBR-000010"), "RWD-WEEKEND").path("lockedByTier").isMissingNode()).isFalse();
        publishFact("io.loyaltyhub.fact.tier.upgraded", "MBR-000010", Map.of("previousTier", "SILVER", "newTier", "GOLD"));
        long deadline = System.currentTimeMillis() + 15_000;
        boolean unlocked = false;
        while (!unlocked && System.currentTimeMillis() < deadline) {
            unlocked = reward(get("/v1/portal/catalog?memberId=MBR-000010"), "RWD-WEEKEND").path("lockedByTier").isMissingNode();
            if (!unlocked) Thread.sleep(300);
        }
        assertThat(unlocked).isTrue();
    }

    @Test
    void bandsKeepUniqueIncreasingThresholdsAndCannotBeDeletedInUse() {
        assertThat(status("POST", "/v1/reward-bands", "ADMIN:test",
                Map.of("code", "F9", "name", "Doppione", "pointsThreshold", 1500, "sortOrder", 9))).isEqualTo(422);
        assertThat(status("POST", "/v1/reward-bands", "ADMIN:test",
                Map.of("code", "F6", "name", "Fuori ordine", "pointsThreshold", 700, "sortOrder", 6))).isEqualTo(422);
        assertThat(status("DELETE", "/v1/reward-bands/F1", "ADMIN:test", null)).isEqualTo(409);
        assertThat(status("POST", "/v1/reward-bands", "MARKETING:giulia",
                Map.of("code", "F6", "name", "Fascia 6", "pointsThreshold", 20000, "sortOrder", 6))).isEqualTo(403);
    }

    @Test
    void rewardLifecycleAndLiveLock() {
        JsonNode created = send("POST", "/v1/rewards", "MARKETING:giulia", Map.of(
                "code", "RWD-IT-MUG", "name", "Tazza Aurora", "type", "PHYSICAL", "category", "CASA", "band", "F1",
                "fulfilment", "MANUAL", "stockTotal", 10), 201);
        String id = created.path("id").asString();
        assertThat(created.path("status").asString()).isEqualTo("DRAFT");
        assertThat(created.path("stockRemaining").asInt()).isEqualTo(10);

        JsonNode live = send("POST", "/v1/rewards/" + id + "/transitions", "MARKETING:giulia", Map.of("action", "SUBMIT"), 200);
        assertThat(live.path("status").asString()).as("approvazione disattivata nel PoC").isEqualTo("LIVE");

        JsonNode restocked = send("PUT", "/v1/rewards/" + id, "MARKETING:giulia", Map.of("stockTotal", 15), 200);
        assertThat(restocked.path("stockTotal").asInt()).isEqualTo(15);
        assertThat(restocked.path("stockRemaining").asInt()).isEqualTo(15);

        JsonNode locked = send("PUT", "/v1/rewards/" + id, "MARKETING:giulia", Map.of("band", "F2"), 409);
        assertThat(locked.path("code").asString()).isEqualTo("REWARD_LIVE_LOCKED");
        assertThat(send("POST", "/v1/rewards/" + id + "/transitions", "MARKETING:giulia", Map.of("action", "RESUME"), 409)
                .path("code").asString()).isEqualTo("INVALID_TRANSITION");

        JsonNode copy = send("POST", "/v1/rewards/" + id + "/duplicate", "MARKETING:giulia", null, 201);
        assertThat(copy.path("code").asString()).isEqualTo("RWD-IT-MUG-COPY");
        assertThat(copy.path("status").asString()).isEqualTo("DRAFT");

        assertThat(send("POST", "/v1/rewards", "MARKETING:giulia", Map.of("code", "RWD-IT-BAD", "name", "x",
                "type", "PHYSICAL", "band", "F1", "fulfilment", "AUTO_COUPON"), 422).path("code").asString())
                .isEqualTo("REWARD_INVALID");
    }

    // ---------- helper ----------

    private static JsonNode reward(JsonNode catalog, String code) {
        for (JsonNode band : catalog.path("bands")) {
            for (JsonNode r : band.path("rewards")) {
                if (r.path("code").asString().equals(code)) {
                    return r;
                }
            }
        }
        return null;
    }

    private JsonNode get(String path) {
        return RestClient.create("http://localhost:" + port).get().uri(path).retrieve().body(JsonNode.class);
    }

    private int status(String method, String path, String actor, Object body) {
        var spec = RestClient.create("http://localhost:" + port).method(org.springframework.http.HttpMethod.valueOf(method)).uri(path);
        if (actor != null) spec = spec.header("X-LH-Actor", actor);
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return spec.exchange((req, res) -> res.getStatusCode().value());
    }

    private JsonNode send(String method, String path, String actor, Object body, int expected) {
        var spec = RestClient.create("http://localhost:" + port).method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(path).header("X-LH-Actor", actor);
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return spec.exchange((req, res) -> {
            assertThat(res.getStatusCode().value()).as(method + " " + path).isEqualTo(expected);
            return mapper.readTree(res.getBody());
        });
    }

    private void publishFact(String type, String memberId, Map<String, Object> data) throws Exception {
        String id = "FACT-" + System.nanoTime();
        Map<String, Object> event = Map.of("specversion", "1.0", "id", id, "source", "urn:loyaltyhub:service:wallet",
                "type", type, "subject", "member:" + memberId, "time", Instant.now().toString(),
                "lhcorrelationid", id, "lhhop", 0, "data", data);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class))) {
            producer.send(new ProducerRecord<>("lh.facts.v1", memberId, mapper.writeValueAsString(event))).get();
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
