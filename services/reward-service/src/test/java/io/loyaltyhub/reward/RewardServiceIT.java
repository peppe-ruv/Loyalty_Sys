package io.loyaltyhub.reward;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RewardServiceIT {

    private static final EmbeddedPostgres PG = startPg();

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcClient jdbc;

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

        JsonNode stats = get("/v1/rewards/stats");
        assertThat(stats.path("rewardsByStatus").path("LIVE").asLong()).isGreaterThanOrEqualTo(10);
        assertThat(stats.path("lowStock").toString()).contains("RWD-SHOP-25", "RWD-POWERBANK").doesNotContain("RWD-DONATION-TREE");
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
            if (!unlocked) Thread.sleep(100);
        }
        assertThat(unlocked).isTrue();
    }

    /** M6.6 (F-RWD-04 P1): {@code member.segment.entered/left} aggiornano lo snapshot; RWD-EBIKE-RENT è per SEG-TORINO. */
    @Test
    void segmentFactsShowAndHideSegmentRewards() throws Exception {
        assertThat(reward(get("/v1/portal/catalog?memberId=MBR-000007"), "RWD-EBIKE-RENT")).isNull();
        publishFact("io.loyaltyhub.fact.member.segment.entered", "MBR-000007", Map.of("segmentCode", "SEG-TORINO"));
        long deadline = System.currentTimeMillis() + 15_000;
        while (reward(get("/v1/portal/catalog?memberId=MBR-000007"), "RWD-EBIKE-RENT") == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(reward(get("/v1/portal/catalog?memberId=MBR-000007"), "RWD-EBIKE-RENT")).as("nel segmento: visibile").isNotNull();
        publishFact("io.loyaltyhub.fact.member.segment.left", "MBR-000007", Map.of("segmentCode", "SEG-TORINO"));
        deadline = System.currentTimeMillis() + 15_000;
        while (reward(get("/v1/portal/catalog?memberId=MBR-000007"), "RWD-EBIKE-RENT") != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(reward(get("/v1/portal/catalog?memberId=MBR-000007"), "RWD-EBIKE-RENT")).as("uscito: di nuovo escluso").isNull();
    }

    // ---------- ricerca nel catalogo: builder SQL con colonne da allowlist (F2-SEC-10, ADR-042) ----------
    // Questi test girano per primi (@Order) sul catalogo seminato da rewards.json: gli altri creano premi.

    @Test
    @Order(1)
    void eachSearchFilterAloneReturnsExactlyTheSeededRewards() {
        assertThat(rewardCodes(Map.of("status", "LIVE"))).containsExactly(
                "RWD-COFFEE-5", "RWD-DONATION-TREE",
                "RWD-BORRACCIA", "RWD-CINEMA-2", "RWD-SHOP-10",
                "RWD-BILL-20", "RWD-POWERBANK", "RWD-SHOP-25",
                "RWD-EBIKE-RENT", "RWD-SMART-PLUG",
                "RWD-PLATINUM-EVENT", "RWD-WEEKEND");
        assertThat(rewardCodes(Map.of("status", "draft"))).containsExactly("RWD-THERMOSTAT");
        assertThat(rewardCodes(Map.of("status", "IN_REVIEW"))).containsExactly("RWD-GIFT-50");

        assertThat(rewardCodes(Map.of("band", "F1"))).containsExactly("RWD-COFFEE-5", "RWD-DONATION-TREE");
        assertThat(rewardCodes(Map.of("band", "F4")))
                .containsExactly("RWD-EBIKE-RENT", "RWD-GIFT-50", "RWD-SMART-PLUG");
        assertThat(rewardCodes(Map.of("band", "F5")))
                .containsExactly("RWD-PLATINUM-EVENT", "RWD-THERMOSTAT", "RWD-WEEKEND");

        assertThat(rewardCodes(Map.of("category", "CASA")))
                .containsExactly("RWD-BILL-20", "RWD-POWERBANK", "RWD-SMART-PLUG", "RWD-THERMOSTAT");
        assertThat(rewardCodes(Map.of("category", "SOLIDALE"))).containsExactly("RWD-DONATION-TREE");
        assertThat(rewardCodes(Map.of("category", "ESPERIENZE"))).containsExactly("RWD-PLATINUM-EVENT", "RWD-WEEKEND");

        assertThat(rewardCodes(Map.of("type", "physical")))
                .containsExactly("RWD-BORRACCIA", "RWD-POWERBANK", "RWD-SMART-PLUG", "RWD-THERMOSTAT");
        assertThat(rewardCodes(Map.of("type", "COUPON")))
                .containsExactly("RWD-COFFEE-5", "RWD-CINEMA-2", "RWD-SHOP-10", "RWD-SHOP-25", "RWD-GIFT-50");
        assertThat(rewardCodes(Map.of("type", "DIGITAL"))).containsExactly("RWD-BILL-20");

        // q cerca, senza distinguere maiuscole e minuscole, nel codice OPPURE nel nome.
        assertThat(rewardCodes(Map.of("q", "shop"))).containsExactly("RWD-SHOP-10", "RWD-SHOP-25");
        assertThat(rewardCodes(Map.of("q", "donation"))).as("solo nel codice").containsExactly("RWD-DONATION-TREE");
        assertThat(rewardCodes(Map.of("q", "COLAZIONE"))).as("solo nel nome").containsExactly("RWD-COFFEE-5");
        assertThat(rewardCodes(Map.of("q", "buono")))
                .containsExactly("RWD-COFFEE-5", "RWD-SHOP-10", "RWD-SHOP-25");
        assertThat(rewardCodes(Map.of("q", "inesistente"))).isEmpty();

        // Filtri combinati: in AND.
        assertThat(rewardCodes(Map.of("status", "LIVE", "band", "F4")))
                .containsExactly("RWD-EBIKE-RENT", "RWD-SMART-PLUG");
        assertThat(rewardCodes(Map.of("category", "TEMPO", "type", "PHYSICAL"))).containsExactly("RWD-BORRACCIA");
    }

    @Test
    @Order(2)
    void searchTextIsMatchedLiterallyWithoutLikeWildcards() {
        send("POST", "/v1/rewards", "MARKETING:giulia", Map.of("code", "RWD-IT-LITERAL",
                "name", "Sconto 50% su_misura dell'Aurora", "type", "DIGITAL", "category", "SOLIDALE", "band", "F1",
                "fulfilment", "MANUAL"), 201);

        assertThat(rewardCodes(Map.of("q", "%"))).containsExactly("RWD-IT-LITERAL");
        assertThat(rewardCodes(Map.of("q", "_"))).containsExactly("RWD-IT-LITERAL");
        assertThat(rewardCodes(Map.of("q", "50%"))).containsExactly("RWD-IT-LITERAL");
        assertThat(rewardCodes(Map.of("q", "dell'Aurora"))).containsExactly("RWD-IT-LITERAL");
        // Come caratteri jolly questi troverebbero premi seminati (RWD-…, RWD-COFFEE-5) o il premio con «%».
        assertThat(rewardCodes(Map.of("q", "R_D"))).isEmpty();
        assertThat(rewardCodes(Map.of("q", "RWD%COFFEE"))).isEmpty();
        assertThat(rewardCodes(Map.of("q", "\\"))).isEmpty();
    }

    @Test
    @Order(3)
    void injectionShapedFiltersFindNothingAndLeaveTheCatalogIntact() {
        List<Map<String, String>> attempts = List.of(
                Map.of("q", "MUG'; DROP TABLE reward; --"),
                Map.of("q", "' OR '1'='1"),
                Map.of("status", "LIVE' OR '1'='1"),
                Map.of("band", "F1'; DELETE FROM reward; --"),
                Map.of("category", "CASA' OR 1=1 --"),
                Map.of("type", "COUPON') OR ('a'='a"));
        for (Map<String, String> params : attempts) {
            long before = rewardRows();
            Reply reply = getReply("/v1/rewards", params);
            assertThat(reply.status()).as(params.toString()).isEqualTo(200);
            assertThat(reply.body().isArray()).as(params.toString()).isTrue();
            assertThat(reply.body().size()).as(params.toString()).isZero();
            assertThat(rewardRows()).as(params.toString()).isEqualTo(before);
        }
    }

    @Test
    void bandsKeepUniqueIncreasingThresholdsAndCannotBeDeletedInUse() {
        assertThat(status("POST", "/v1/reward-bands", "ADMIN:test",
                Map.of("code", "F9", "name", "Doppione", "pointsThreshold", 1500, "sortOrder", 9))).isEqualTo(422);
        assertThat(status("POST", "/v1/reward-bands", "ADMIN:test",
                Map.of("code", "F6", "name", "Fuori ordine", "pointsThreshold", 700, "sortOrder", 6))).isEqualTo(422);
        assertThat(status("DELETE", "/v1/reward-bands/F1", "ADMIN:test", null)).isEqualTo(409);
        assertThat(status("POST", "/v1/reward-bands", "CARE:paolo",
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

        // M7.1: i premi si approvano sempre da LEGAL (docs/06 §7); il rifiuto riporta in bozza.
        assertThat(send("POST", "/v1/rewards/" + id + "/transitions", "MARKETING:giulia", Map.of("action", "PUBLISH"), 409)
                .path("code").asString()).isEqualTo("APPROVAL_REQUIRED");
        assertThat(send("POST", "/v1/rewards/" + id + "/transitions", "MARKETING:giulia", Map.of("action", "SUBMIT"), 200)
                .path("status").asString()).isEqualTo("IN_REVIEW");
        assertThat(send("POST", "/v1/rewards/" + id + "/transitions", "MARKETING:giulia", Map.of("action", "APPROVE"), 403)
                .path("code").asString()).isEqualTo("FORBIDDEN_ROLE");
        assertThat(send("POST", "/v1/rewards/" + id + "/transitions", "LEGAL:elena", Map.of("action", "REJECT", "comment", "Termini mancanti"), 200)
                .path("status").asString()).isEqualTo("DRAFT");
        send("POST", "/v1/rewards/" + id + "/transitions", "MARKETING:giulia", Map.of("action", "SUBMIT"), 200);
        assertThat(send("POST", "/v1/rewards/" + id + "/transitions", "LEGAL:elena", Map.of("action", "APPROVE"), 200)
                .path("status").asString()).isEqualTo("APPROVED");
        JsonNode live = send("POST", "/v1/rewards/" + id + "/transitions", "MARKETING:giulia", Map.of("action", "PUBLISH"), 200);
        assertThat(live.path("status").asString()).isEqualTo("LIVE");
        JsonNode history = send("GET", "/v1/rewards/" + id + "/approval-history", "ANALYST:sara", null, 200);
        assertThat(history).hasSize(5);
        assertThat(history.get(3).path("comment").asString()).isEqualTo("Termini mancanti");
        assertThat(send("GET", "/v1/approvals", "LEGAL:elena", null, 200).toString()).contains("RWD-GIFT-50");

        JsonNode restocked = send("PUT", "/v1/rewards/" + id, "MARKETING:giulia",
                Map.of("stockTotal", 15, "version", live.path("version").asLong()), 200);
        assertThat(restocked.path("stockTotal").asInt()).isEqualTo(15);
        assertThat(restocked.path("stockRemaining").asInt()).isEqualTo(15);
        // Versioni (M7.6): un salvataggio con la versione letta prima dell'ultima modifica è rifiutato.
        long stale = live.path("version").asLong();
        assertThat(send("PUT", "/v1/rewards/" + id, "MARKETING:luca", Map.of("stockTotal", 20, "version", stale), 409)
                .path("code").asString()).isEqualTo("VERSION_CONFLICT");
        assertThat(send("PUT", "/v1/rewards/" + id, "MARKETING:luca",
                Map.of("stockTotal", 16, "version", restocked.path("version").asLong()), 200).path("stockRemaining").asInt())
                .isEqualTo(16);

        JsonNode locked = send("PUT", "/v1/rewards/" + id, "MARKETING:giulia",
                Map.of("band", "F2", "version", restocked.path("version").asLong() + 1), 409);
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

    private record Reply(int status, JsonNode body) {
    }

    private List<String> rewardCodes(Map<String, String> params) {
        Reply reply = getReply("/v1/rewards", params);
        assertThat(reply.status()).as("GET /v1/rewards " + params).isEqualTo(200);
        List<String> codes = new ArrayList<>();
        reply.body().forEach(r -> codes.add(r.path("code").asString()));
        return codes;
    }

    /** GET con i parametri codificati per esteso (anche {@code %}, {@code +}, {@code '} e la barra rovesciata). */
    private Reply getReply(String path, Map<String, String> params) {
        StringJoiner query = new StringJoiner("&", "?", "").setEmptyValue("");
        params.forEach((k, v) -> query.add(k + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        URI uri = URI.create("http://localhost:" + port + path + query);
        return RestClient.create().get().uri(uri).exchange((req, res) ->
                new Reply(res.getStatusCode().value(), mapper.readTree(res.getBody())));
    }

    private long rewardRows() {
        return jdbc.sql("SELECT count(*) FROM reward").query(Long.class).single();
    }

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
