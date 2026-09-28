package io.loyaltyhub.reward;

import io.loyaltyhub.common.testsupport.ListenerGroups;
import io.loyaltyhub.common.testsupport.TopicReader;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4.2 pool coupon (docs/servizi/reward-service.md §3, §5, §7; F-CPN-01..03): seed deterministico, generazione e
 * import, emissione da effetto {@code coupon.issue} idempotente, cassa simulata (uso, annullo, scadenza) e pool vuoto
 * → DLQ {@code COUPON_POOL_EMPTY} senza nuovi tentativi. Senza Docker: EmbeddedKafka + Zonky.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CouponIT {

    private static final EmbeddedPostgres PG = startPg();

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=reward");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    /** Si pubblica solo a gruppo {@code lh-reward} stabile, con le partizioni assegnate ({@link ListenerGroups}). */
    @BeforeAll
    void waitForListenerGroup() {
        ListenerGroups.awaitStable(listeners);
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    void seededPoolsAreDeterministicAndLinkedToTheirRewards() {
        JsonNode pools = get("/v1/coupon-pools");
        assertThat(pools.size()).isEqualTo(5);
        JsonNode shp25 = pool(pools, "POOL-SHP25");
        assertThat(shp25.path("counts").path("AVAILABLE").asLong()).isEqualTo(9);
        assertThat(shp25.path("counts").path("USED").asLong()).isEqualTo(141);
        assertThat(shp25.path("rewards").get(0).path("code").asString()).isEqualTo("RWD-SHOP-25");

        // Due reset consecutivi (gli altri test possono aver già emesso codici): stessi codici liberi.
        send("POST", "/v1/demo/reset", "ADMIN:test", null, 200);
        List<String> before = codes(pool(get("/v1/coupon-pools"), "POOL-CAF").path("id").asString());
        assertThat(before.getFirst()).matches("^CAF-[A-Z2-9]{4}-[A-Z2-9]{4}$");

        send("POST", "/v1/demo/reset", "ADMIN:test", null, 200);
        String cafAfter = pool(get("/v1/coupon-pools"), "POOL-CAF").path("id").asString();
        assertThat(codes(cafAfter)).as("stesso seme → stessi codici dopo il reset").isEqualTo(before);
    }

    @Test
    void generateAndImportRespectLimitsAndRoles() {
        JsonNode created = send("POST", "/v1/coupon-pools", "MARKETING:giulia",
                Map.of("code", "POOL-IT-GEN", "name", "Pool di prova", "prefix", "ITG", "validityDays", 30), 201);
        String id = created.path("id").asString();
        assertThat(created.path("total").asLong()).isZero();

        JsonNode gen = send("POST", "/v1/coupon-pools/" + id + "/generate", "MARKETING:giulia", Map.of("count", 3000), 200);
        assertThat(gen.path("generated").asInt()).isEqualTo(3000);
        assertThat(gen.path("available").asLong()).isEqualTo(3000);
        assertThat(send("POST", "/v1/coupon-pools/" + id + "/generate", "MARKETING:giulia", Map.of("count", 5001), 422)
                .path("code").asString()).isEqualTo("COUPON_COUNT_INVALID");
        assertThat(status("POST", "/v1/coupon-pools/" + id + "/generate", "CARE:paolo", Map.of("count", 1))).isEqualTo(403);

        String existing = codes(id).getFirst();
        JsonNode imported = send("POST", "/v1/coupon-pools/" + id + "/import", "ADMIN:test",
                Map.of("codes", List.of("ITG-IMPT-0001", "itg-impt-0001", "x", existing, " ITG-IMPT-0002 ")), 200);
        assertThat(imported.path("imported").asInt()).isEqualTo(2);
        assertThat(imported.path("skipped").size()).isEqualTo(3);
        assertThat(get("/v1/coupons/ITG-IMPT-0002").path("status").asString()).isEqualTo("AVAILABLE");

        JsonNode page = get("/v1/coupon-pools/" + id + "/coupons?status=AVAILABLE&size=10");
        assertThat(page.path("items").size()).isEqualTo(10);
        assertThat(page.path("page").path("totalItems").asLong()).isEqualTo(3002);
    }

    @Test
    void couponIssueEffectIsIdempotentAndTheTillUsesItOnce() throws Exception {
        String effectId = "EFF-IT-" + System.nanoTime();
        // Giulia (MBR-000003) non ha coupon colazione nello storico seminato.
        publishEffect("MBR-000003", effectId, "RWD-COFFEE-5");
        JsonNode coupon = awaitCoupon("MBR-000003", "RWD-COFFEE-5");
        String code = coupon.path("code").asString();
        assertThat(coupon.path("status").asString()).isEqualTo("ISSUED");
        assertThat(coupon.path("origin").asString()).isEqualTo("CAMPAIGN");
        assertThat(coupon.path("rewardName").asString()).isEqualTo("Buono colazione 5 €");

        // Stesso effectId in un nuovo messaggio (id diverso): nessun secondo coupon.
        RecordMetadata again = publishEffect("MBR-000003", effectId, "RWD-COFFEE-5");
        // Barriera esatta: il secondo messaggio è elaborato (offset confermato), un secondo coupon non può più arrivare.
        ListenerGroups.awaitCommitted(listeners, List.of(again));
        assertThat(countFor("MBR-000003", "RWD-COFFEE-5")).isEqualTo(1);

        assertThat(send("POST", "/v1/coupons/" + code + "/use", "CARE:paolo", null, 200).path("status").asString()).isEqualTo("USED");
        assertThat(send("POST", "/v1/coupons/" + code + "/use", "CARE:paolo", null, 409).path("code").asString())
                .isEqualTo("COUPON_ALREADY_USED");
        assertThat(send("POST", "/v1/coupons/" + code + "/void", "ADMIN:test", null, 409).path("code").asString())
                .isEqualTo("COUPON_ALREADY_USED");
        assertThat(status("POST", "/v1/coupons/" + code + "/use", "ANALYST:test", null)).isEqualTo(403);
        assertThat(status("GET", "/v1/coupons/NOPE-0000-0000", null, null)).isEqualTo(404);
    }

    @Test
    void availableCodesCannotBeUsedButCanBeVoidedAndIssuedOnesExpire() throws Exception {
        String caf = pool(get("/v1/coupon-pools"), "POOL-CIN").path("id").asString();
        String available = get("/v1/coupon-pools/" + caf + "/coupons?status=AVAILABLE&size=1").path("items").get(0).path("code").asString();
        assertThat(send("POST", "/v1/coupons/" + available + "/use", "CARE:paolo", null, 409).path("code").asString())
                .isEqualTo("COUPON_NOT_ISSUED");
        assertThat(status("POST", "/v1/coupons/" + available + "/void", "MARKETING:giulia", null)).isEqualTo(403);
        assertThat(send("POST", "/v1/coupons/" + available + "/void", "CARE:paolo", null, 200).path("status").asString()).isEqualTo("VOID");

        publishEffect("MBR-000007", "EFF-IT-EXP-" + System.nanoTime(), "RWD-CINEMA-2");
        String code = awaitCoupon("MBR-000007", "RWD-CINEMA-2").path("code").asString();
        String asOf = LocalDate.now().plusDays(200).toString();
        JsonNode job = send("POST", "/v1/demo/jobs/expire-coupons?asOf=" + asOf, "ADMIN:test", null, 200);
        assertThat(job.path("coupons").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(get("/v1/coupons/" + code).path("status").asString()).isEqualTo("EXPIRED");
        assertThat(send("POST", "/v1/coupons/" + code + "/use", "CARE:paolo", null, 410).path("code").asString())
                .isEqualTo("COUPON_EXPIRED");
    }

    // ---------- ricerca dei codici: builder SQL con colonne da allowlist (F2-SEC-10, ADR-042) ----------
    // Questi test girano per primi (@Order) sui pool seminati e non scrivono nulla: gli altri test emettono, usano e
    // annullano codici e fanno reset. POOL-CIN: 220 codici; lo storico seminato (redemptions.json) ne ha emessi 3
    // (RDM-000002 ISSUED, RDM-000017 EXPIRED, RDM-000023 ISSUED). POOL-SHP25: 150 codici, 141 consumati.

    @Test
    @Order(1)
    void eachSearchFilterAloneReturnsExactlyTheSeededCodes() {
        String cin = pool(get("/v1/coupon-pools"), "POOL-CIN").path("id").asString();
        String caf = pool(get("/v1/coupon-pools"), "POOL-CAF").path("id").asString();
        String shp25 = pool(get("/v1/coupon-pools"), "POOL-SHP25").path("id").asString();
        String cinDavide = couponOf("RDM-000002");
        String cinExpired = couponOf("RDM-000017");
        String cinIssued = couponOf("RDM-000023");

        // Solo il pool: tutti e soli i suoi codici.
        JsonNode all = couponPage(cin, Map.of("size", "100"));
        assertThat(all.path("page").path("totalItems").asLong()).isEqualTo(220);
        assertThat(all.path("items").size()).isEqualTo(100);
        all.path("items").forEach(c -> {
            assertThat(c.path("code").asString()).startsWith("CIN-");
            assertThat(c.path("poolId").asString()).isEqualTo(cin);
        });
        assertThat(total(shp25, Map.of())).isEqualTo(150);

        // Stato.
        assertThat(total(cin, Map.of("status", "AVAILABLE"))).isEqualTo(217);
        assertThat(couponCodes(cin, Map.of("status", "issued"))).containsExactlyInAnyOrder(cinDavide, cinIssued);
        assertThat(couponCodes(cin, Map.of("status", "EXPIRED"))).containsExactly(cinExpired);
        assertThat(couponCodes(cin, Map.of("status", "USED"))).isEmpty();
        assertThat(total(shp25, Map.of("status", "USED"))).isEqualTo(141);
        assertThat(total(shp25, Map.of("status", "AVAILABLE"))).isEqualTo(9);

        // Membro, sempre dentro il pool del percorso.
        assertThat(couponCodes(cin, Map.of("memberId", "MBR-000004"))).containsExactly(cinDavide);
        assertThat(couponCodes(cin, Map.of("memberId", "MBR-000007"))).containsExactly(cinExpired);
        assertThat(couponCodes(caf, Map.of("memberId", "MBR-000004"))).containsExactly(couponOf("RDM-000001"));
        assertThat(couponCodes(caf, Map.of("memberId", "MBR-000007"))).as("coupon di un altro pool").isEmpty();
        assertThat(couponCodes(cin, Map.of("status", "ISSUED", "memberId", "MBR-000007"))).isEmpty();
    }

    @Test
    @Order(2)
    void pagesAreDisjointAndFollowTheStableOrder() {
        String cin = pool(get("/v1/coupon-pools"), "POOL-CIN").path("id").asString();
        List<String> page0 = couponCodes(cin, Map.of("size", "2", "page", "0"));
        List<String> page1 = couponCodes(cin, Map.of("size", "2", "page", "1"));
        List<String> firstFour = couponCodes(cin, Map.of("size", "4", "page", "0"));

        assertThat(page0).hasSize(2).doesNotContainAnyElementsOf(page1);
        assertThat(page1).hasSize(2);
        List<String> joined = new ArrayList<>(page0);
        joined.addAll(page1);
        assertThat(joined).isEqualTo(firstFour);
    }

    @Test
    @Order(3)
    void injectionShapedFiltersFindNothingAndLeaveTheTableIntact() {
        String cin = pool(get("/v1/coupon-pools"), "POOL-CIN").path("id").asString();
        List<Map<String, String>> attempts = List.of(
                Map.of("status", "AVAILABLE').-"),
                Map.of("status", "AVAILABLE'; DROP TABLE coupon; --"),
                Map.of("status", "AVAILABLE' OR '1'='1"),
                Map.of("memberId", "MBR-000007' OR '1'='1"),
                Map.of("memberId", "x'; DELETE FROM coupon; --"));
        for (Map<String, String> params : attempts) {
            long before = couponRows();
            Reply reply = getReply("/v1/coupon-pools/" + cin + "/coupons", params);
            assertThat(reply.status()).as(params.toString()).isEqualTo(200);
            assertThat(reply.body().path("items").isArray()).as(params.toString()).isTrue();
            assertThat(reply.body().path("items").size()).as(params.toString()).isZero();
            assertThat(reply.body().path("page").path("totalItems").asLong()).as(params.toString()).isZero();
            assertThat(couponRows()).as(params.toString()).isEqualTo(before);
        }
    }

    @Test
    void emptyPoolSendsTheEffectToTheDlqWithoutRetries() throws Exception {
        String poolId = send("POST", "/v1/coupon-pools", "ADMIN:test",
                Map.of("code", "POOL-IT-EMPTY", "name", "Vuoto", "prefix", "EMP"), 201).path("id").asString();
        send("POST", "/v1/rewards", "ADMIN:test", Map.of("code", "RWD-IT-EMPTY", "name", "Buono vuoto", "type", "COUPON",
                "band", "F1", "fulfilment", "AUTO_COUPON", "couponPoolId", poolId), 201);

        {
            String effectId = "EFF-IT-EMPTY-" + System.nanoTime();
            RecordMetadata sent = publishEffect("MBR-000002", effectId, "RWD-IT-EMPTY");
            // Barriera esatta: l'offset è confermato solo dopo la pubblicazione in DLQ (commitRecovered); poi lh.dlq.v1 si
            // legge fino in fondo, senza consumer group né finestra: una seconda copia resterebbe visibile.
            ListenerGroups.awaitCommitted(listeners, List.of(sent));
            String code = null;
            int copies = 0;
            for (ConsumerRecord<String, String> r : new TopicReader(jdbc, mapper, "lh.dlq.v1")
                    .records(List.of(), r -> r.value().contains(effectId))) {
                copies++;
                Header h = r.headers().lastHeader("lh-error-code");
                code = h == null ? "" : new String(h.value(), StandardCharsets.UTF_8);
            }
            assertThat(code).isEqualTo("COUPON_POOL_EMPTY");
            assertThat(copies).isEqualTo(1);
        }
    }

    // ---------- helper ----------

    private record Reply(int status, JsonNode body) {
    }

    private String couponOf(String redemptionId) {
        String code = get("/v1/redemptions/" + redemptionId).path("couponCode").asString();
        assertThat(code).as("coupon di " + redemptionId).isNotBlank();
        return code;
    }

    private JsonNode couponPage(String poolId, Map<String, String> params) {
        Reply reply = getReply("/v1/coupon-pools/" + poolId + "/coupons", params);
        assertThat(reply.status()).as("GET coupons " + params).isEqualTo(200);
        return reply.body();
    }

    private long total(String poolId, Map<String, String> params) {
        return couponPage(poolId, params).path("page").path("totalItems").asLong();
    }

    /** Codici della pagina (al più 100); senza {@code page} verifica anche che {@code totalItems} sia coerente. */
    private List<String> couponCodes(String poolId, Map<String, String> filters) {
        Map<String, String> params = new LinkedHashMap<>(filters);
        params.putIfAbsent("size", "100");
        JsonNode page = couponPage(poolId, params);
        List<String> codes = new ArrayList<>();
        page.path("items").forEach(c -> codes.add(c.path("code").asString()));
        if (!params.containsKey("page")) {
            assertThat(page.path("page").path("totalItems").asLong()).as("count " + params).isEqualTo(codes.size());
        }
        return codes;
    }

    /** GET con i parametri codificati per esteso (anche {@code %}, {@code +} e {@code '}). */
    private Reply getReply(String path, Map<String, String> params) {
        StringJoiner query = new StringJoiner("&", "?", "").setEmptyValue("");
        params.forEach((k, v) -> query.add(k + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        URI uri = URI.create("http://localhost:" + port + path + query);
        return RestClient.create().get().uri(uri).exchange((req, res) ->
                new Reply(res.getStatusCode().value(), mapper.readTree(res.getBody())));
    }

    private long couponRows() {
        return jdbc.sql("SELECT count(*) FROM coupon").query(Long.class).single();
    }

    private static JsonNode pool(JsonNode pools, String code) {
        for (JsonNode p : pools) {
            if (p.path("code").asString().equals(code)) {
                return p;
            }
        }
        throw new AssertionError("pool assente: " + code);
    }

    private List<String> codes(String poolId) {
        List<String> out = new ArrayList<>();
        get("/v1/coupon-pools/" + poolId + "/coupons?status=AVAILABLE&size=20").path("items")
                .forEach(c -> out.add(c.path("code").asString()));
        return out.stream().sorted().toList();
    }

    private JsonNode awaitCoupon(String memberId, String rewardCode) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            for (JsonNode c : get("/v1/portal/coupons?memberId=" + memberId)) {
                if (c.path("rewardCode").asString().equals(rewardCode) && c.path("status").asString().equals("ISSUED")) {
                    return c;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("nessun coupon " + rewardCode + " per " + memberId);
    }

    private long countFor(String memberId, String rewardCode) {
        long n = 0;
        for (JsonNode c : get("/v1/portal/coupons?memberId=" + memberId)) {
            if (c.path("rewardCode").asString().equals(rewardCode)) {
                n++;
            }
        }
        return n;
    }

    /** AUD-BE-10 / AUD-BE-09: pagina al massimo 100 elementi (docs/06 §2), anche se il client ne chiede di più. */
    @Test
    void pageSizeIsCappedAt100() {
        String caf = pool(get("/v1/coupon-pools"), "POOL-CAF").path("id").asString();
        JsonNode coupons = get("/v1/coupon-pools/" + caf + "/coupons?size=150");
        assertThat(coupons.path("page").path("size").asInt()).isEqualTo(100);
        assertThat(coupons.path("items").size()).isLessThanOrEqualTo(100);
        assertThat(get("/v1/redemptions?size=150").path("page").path("size").asInt()).isEqualTo(100);
    }

    private JsonNode get(String path) {
        return RestClient.create("http://localhost:" + port).get().uri(path).retrieve().body(JsonNode.class);
    }

    private int status(String method, String path, String actor, Object body) {
        var spec = RestClient.create("http://localhost:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (actor != null) spec = spec.header("X-LH-Actor", actor);
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

    private RecordMetadata publishEffect(String memberId, String effectId, String rewardCode) throws Exception {
        String id = "EVT-" + System.nanoTime();
        Map<String, Object> event = Map.of("specversion", "1.0", "id", id, "source", "urn:loyaltyhub:service:campaign",
                "type", "io.loyaltyhub.effect.coupon.issue", "subject", "member:" + memberId,
                "time", Instant.now().toString(), "lhcorrelationid", id, "lhhop", 0,
                "data", Map.of("effectId", effectId, "campaignCode", "CMP-IT-COUPON", "actionId", "ACT-" + id,
                        "actionType", "instantwin.won", "rewardCode", rewardCode));
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class))) {
            return producer.send(new ProducerRecord<>("lh.effects.v1", memberId, mapper.writeValueAsString(event))).get();
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
