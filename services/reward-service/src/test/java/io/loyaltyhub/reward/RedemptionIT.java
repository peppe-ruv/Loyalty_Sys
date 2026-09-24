package io.loyaltyhub.reward;

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
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4.3 saga di richiesta premio, lato reward (docs/servizi/reward-service.md §5, §7; F-RWD-05): validazioni 422, stock
 * atomico, conferma ed evasione al {@code wallet.points.spent}, rifiuto, timeout, compensazione della spesa tardiva,
 * annullo del membro, idempotenza. Il wallet è simulato pubblicandone i fatti. Senza Docker: EmbeddedKafka + Zonky.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedemptionIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final Map<String, Object> ADDRESS = Map.of("name", "Sofia Test", "street", "Via Roma 1", "city", "Torino", "zip", "10100");

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
        // Il job del timeout lo lanciamo a mano con asOf: quello schedulato non deve interferire.
        registry.add("loyaltyhub.reward.redemption-timeout.enabled", () -> "false");
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    void couponRewardIsConfirmedAndFulfilledWhenTheWalletSpends() throws Exception {
        int stockBefore = reward("RWD-SHOP-10").path("stockRemaining").asInt();
        JsonNode accepted = request("MBR-000004", "RWD-SHOP-10", null, 202);
        String id = accepted.path("redemptionId").asString();
        assertThat(accepted.path("status").asString()).isEqualTo("PENDING");
        assertThat(reward("RWD-SHOP-10").path("stockRemaining").asInt()).as("stock prenotato").isEqualTo(stockBefore - 1);

        JsonNode requested = awaitFact("io.loyaltyhub.fact.reward.redemption.requested", id);
        assertThat(requested.path("data").path("pointsCost").asLong()).isEqualTo(1500);
        // Accettazione M4: qui il wallet non c'è ("fermo") → la richiesta resta PENDING finché non risponde.
        Thread.sleep(1500);
        assertThat(get("/v1/portal/redemptions/" + id).path("status").asString()).isEqualTo("PENDING");
        assertThat(requested.path("id").asString()).isEqualTo(accepted.path("correlationId").asString());

        String spentId = publishWalletFact(requested, "io.loyaltyhub.fact.wallet.points.spent",
                Map.of("ledgerEntryId", "LE-" + id, "currency", "PTS", "amount", 1500, "balanceAfter", 10800, "redemptionId", id));
        JsonNode done = awaitStatus(id, "FULFILLED");
        String coupon = done.path("couponCode").asString();
        assertThat(coupon).startsWith("SHP10-");
        assertThat(done.path("history").toString()).contains("PENDING", "CONFIRMED", "FULFILLED");

        JsonNode fulfilled = awaitFact("io.loyaltyhub.fact.reward.redemption.fulfilled", id);
        assertThat(fulfilled.path("lhcorrelationid").asString()).as("stesso tracciato della richiesta").isEqualTo(requested.path("id").asString());
        JsonNode confirmed = awaitFact("io.loyaltyhub.fact.reward.redemption.confirmed", id);
        assertThat(confirmed.path("lhcausationid").asString()).isEqualTo(spentId);

        // Rielaborazione della spesa (nuovo messaggio, stessa richiesta): nessun secondo coupon.
        publishWalletFact(requested, "io.loyaltyhub.fact.wallet.points.spent",
                Map.of("ledgerEntryId", "LE-" + id, "currency", "PTS", "amount", 1500, "balanceAfter", 10800, "redemptionId", id));
        Thread.sleep(1500);
        long coupons = 0;
        for (JsonNode c : get("/v1/portal/coupons?memberId=MBR-000004")) {
            if (id.equals(get("/v1/coupons/" + c.path("code").asString()).path("redemptionId").asString())) {
                coupons++;
            }
        }
        assertThat(coupons).isEqualTo(1);
        assertThat(get("/v1/coupons/" + coupon).path("status").asString()).isEqualTo("ISSUED");
    }

    @Test
    void walletRejectionRejectsAndRestoresStock() throws Exception {
        int stockBefore = reward("RWD-COFFEE-5").path("stockRemaining").asInt();
        String id = request("MBR-000001", "RWD-COFFEE-5", null, 202).path("redemptionId").asString();
        JsonNode requested = awaitFact("io.loyaltyhub.fact.reward.redemption.requested", id);
        publishWalletFact(requested, "io.loyaltyhub.fact.wallet.spend.rejected",
                Map.of("redemptionId", id, "reason", "INSUFFICIENT_BALANCE", "requested", 500, "available", 100));
        JsonNode r = awaitStatus(id, "REJECTED");
        assertThat(r.path("rejectReason").asString()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(reward("RWD-COFFEE-5").path("stockRemaining").asInt()).isEqualTo(stockBefore);
        awaitFact("io.loyaltyhub.fact.reward.redemption.rejected", id);
    }

    @Test
    void immediateValidationsAnswer422WithoutEvents() {
        assertThat(request("MBR-000002", "RWD-WEEKEND", null, 422).path("code").asString()).isEqualTo("TIER_NOT_ELIGIBLE");
        assertThat(request("MBR-000004", "RWD-THERMOSTAT", ADDRESS, 422).path("code").asString()).isEqualTo("REWARD_NOT_AVAILABLE");
        assertThat(request("MBR-000004", "RWD-POWERBANK", ADDRESS, 422).path("code").asString()).isEqualTo("REWARD_SOLD_OUT");
        assertThat(request("MBR-000004", "RWD-BORRACCIA", null, 422).path("code").asString()).isEqualTo("SHIPPING_REQUIRED");
        assertThat(request("MBR-000008", "RWD-COFFEE-5", null, 422).path("code").asString()).isEqualTo("MEMBER_NOT_ACTIVE");
        // RWD-BILL-20: al massimo 2 per membro (Elisa non ne ha nello storico seminato).
        request("MBR-000009", "RWD-BILL-20", null, 202);
        request("MBR-000009", "RWD-BILL-20", null, 202);
        assertThat(request("MBR-000009", "RWD-BILL-20", null, 422).path("code").asString()).isEqualTo("MEMBER_LIMIT_REACHED");
    }

    @Test
    void lastUnitGoesToExactlyOneOfTwoConcurrentRequests() throws Exception {
        String rewardId = send("POST", "/v1/rewards", "ADMIN:test", Map.of("code", "RWD-IT-LAST", "name", "Ultimo pezzo",
                "type", "DIGITAL", "band", "F1", "fulfilment", "INSTANT", "stockTotal", 1), 201).path("id").asString();
        // ADMIN decide anche al posto di LEGAL (override, M7.1).
        send("POST", "/v1/rewards/" + rewardId + "/transitions", "ADMIN:test", Map.of("action", "SUBMIT"), 200);
        send("POST", "/v1/rewards/" + rewardId + "/transitions", "ADMIN:test", Map.of("action", "APPROVE"), 200);
        send("POST", "/v1/rewards/" + rewardId + "/transitions", "ADMIN:test", Map.of("action", "PUBLISH"), 200);

        CompletableFuture<Integer> a = CompletableFuture.supplyAsync(() -> status("MBR-000003", "RWD-IT-LAST"));
        CompletableFuture<Integer> b = CompletableFuture.supplyAsync(() -> status("MBR-000005", "RWD-IT-LAST"));
        List<Integer> codes = List.of(a.get(), b.get());
        assertThat(codes).containsExactlyInAnyOrder(202, 422);
        assertThat(reward("RWD-IT-LAST").path("stockRemaining").asInt()).isZero();
    }

    @Test
    void timeoutRejectsAndALateSpendIsCompensatedWithARefund() throws Exception {
        int stockBefore = reward("RWD-CINEMA-2").path("stockRemaining").asInt();
        String id = request("MBR-000007", "RWD-CINEMA-2", null, 202).path("redemptionId").asString();
        JsonNode requested = awaitFact("io.loyaltyhub.fact.reward.redemption.requested", id);

        String asOf = Instant.now().plus(Duration.ofMinutes(11)).toString();
        JsonNode job = send("POST", "/v1/demo/jobs/timeout-redemptions?asOf=" + asOf, "ADMIN:test", null, 200);
        assertThat(job.path("redemptions").asInt()).isGreaterThanOrEqualTo(1);
        JsonNode r = get("/v1/redemptions/" + id);
        assertThat(r.path("status").asString()).isEqualTo("REJECTED");
        assertThat(r.path("rejectReason").asString()).isEqualTo("TIMEOUT");
        assertThat(reward("RWD-CINEMA-2").path("stockRemaining").asInt()).isEqualTo(stockBefore);
        assertThat(awaitFact("io.loyaltyhub.fact.reward.redemption.rejected", id).path("lhcorrelationid").asString())
                .isEqualTo(requested.path("id").asString());

        // Il wallet si risveglia e spende: la richiesta resta REJECTED e si chiede il rimborso.
        publishWalletFact(requested, "io.loyaltyhub.fact.wallet.points.spent",
                Map.of("ledgerEntryId", "LE-" + id, "currency", "PTS", "amount", 1500, "balanceAfter", 900, "redemptionId", id));
        JsonNode cancelled = awaitFact("io.loyaltyhub.fact.reward.redemption.cancelled", id);
        assertThat(cancelled.path("data").path("refund").asBoolean()).isTrue();
        assertThat(cancelled.path("data").path("pointsCost").asLong()).isEqualTo(1500);
        assertThat(get("/v1/redemptions/" + id).path("status").asString()).isEqualTo("REJECTED");
    }

    @Test
    void memberCancelsWhilePendingAndManualOrInstantRewardsBehave() throws Exception {
        String pending = request("MBR-000011", "RWD-DONATION-TREE", null, 202).path("redemptionId").asString();
        JsonNode cancelled = send("POST", "/v1/portal/redemptions/" + pending + "/cancel?memberId=MBR-000011", "ANALYST:portal", null, 200);
        assertThat(cancelled.path("status").asString()).isEqualTo("CANCELLED");
        assertThat(send("POST", "/v1/portal/redemptions/" + pending + "/cancel?memberId=MBR-000011", "ANALYST:portal", null, 409)
                .path("code").asString()).isEqualTo("REDEMPTION_NOT_CANCELLABLE");
        assertThat(awaitFact("io.loyaltyhub.fact.reward.redemption.cancelled", pending).path("data").path("refund").asBoolean()).isFalse();

        // INSTANT (donazione): evasa subito alla conferma.
        String instant = request("MBR-000011", "RWD-DONATION-TREE", null, 202).path("redemptionId").asString();
        publishWalletFact(awaitFact("io.loyaltyhub.fact.reward.redemption.requested", instant), "io.loyaltyhub.fact.wallet.points.spent",
                Map.of("ledgerEntryId", "LE-" + instant, "currency", "PTS", "amount", 500, "balanceAfter", 6400, "redemptionId", instant));
        assertThat(awaitStatus(instant, "FULFILLED").path("couponCode").isMissingNode()).isTrue();

        // MANUAL (fisico): resta CONFIRMED in coda a BO-13.
        String manual = request("MBR-000011", "RWD-BORRACCIA", ADDRESS, 202).path("redemptionId").asString();
        publishWalletFact(awaitFact("io.loyaltyhub.fact.reward.redemption.requested", manual), "io.loyaltyhub.fact.wallet.points.spent",
                Map.of("ledgerEntryId", "LE-" + manual, "currency", "PTS", "amount", 1500, "balanceAfter", 4900, "redemptionId", manual));
        JsonNode confirmed = awaitStatus(manual, "CONFIRMED");
        assertThat(confirmed.path("shipping").path("city").asString()).isEqualTo("Torino");
        assertThat(get("/v1/portal/redemptions?memberId=MBR-000011").size()).isGreaterThanOrEqualTo(3);
        assertThat(get("/v1/redemptions?status=CONFIRMED").path("items").toString()).contains(manual);
    }

    // ---------- M4.4: operatore (BO-13) e storico seminato ----------

    @Test
    void seededHistoryHasTheDemoStories() {
        // Solo fatti che gli altri test non cambiano (l'ordine dei test non è fissato): Sofia e RDM-000006 li
        // evadono i test dell'operatore, che ne verificano lo stato di partenza.
        JsonNode sofia = get("/v1/redemptions/RDM-000003");
        assertThat(sofia.path("shipping").path("city").asString()).isEqualTo("Bologna");
        assertThat(sofia.path("history").toString()).contains("PENDING", "CONFIRMED");
        assertThat(get("/v1/redemptions?status=REJECTED,CANCELLED&size=50").path("items").toString())
                .contains("RDM-000004", "RDM-000005", "RDM-000018").doesNotContain("RDM-000001");
        JsonNode francesca = get("/v1/redemptions/RDM-000004");
        assertThat(francesca.path("status").asString()).isEqualTo("CANCELLED");
        assertThat(francesca.path("history").toString()).contains("rimborso");

        long issued = 0;
        for (JsonNode c : get("/v1/portal/coupons?memberId=MBR-000004")) {
            if (c.path("status").asString().equals("ISSUED")) {
                issued++;
            }
        }
        assertThat(issued).as("i 2 coupon di Davide").isGreaterThanOrEqualTo(2);
    }

    @Test
    void careFulfilsAManualRequestWithNoteAndTracking() {
        assertThat(get("/v1/redemptions?status=CONFIRMED&fulfilment=MANUAL").path("items").toString())
                .as("scheda «Da evadere» di BO-13").contains("RDM-000003").doesNotContain("RDM-000006");
        assertThat(get("/v1/redemptions/RDM-000003").path("status").asString()).as("Sofia, in attesa di spedizione").isEqualTo("CONFIRMED");
        assertThat(send("POST", "/v1/redemptions/RDM-000003/fulfil", "MARKETING:luca.marketing",
                Map.of("note", "Spedito"), 403).path("code").asString()).isEqualTo("FORBIDDEN_ROLE");
        assertThat(send("POST", "/v1/redemptions/RDM-000003/fulfil", "CARE:anna.care", Map.of("note", " "), 422)
                .path("code").asString()).isEqualTo("NOTE_REQUIRED");
        JsonNode done = send("POST", "/v1/redemptions/RDM-000003/fulfil", "CARE:anna.care",
                Map.of("note", "Spedito con corriere", "tracking", "AUR-77120"), 200);
        assertThat(done.path("status").asString()).isEqualTo("FULFILLED");
        assertThat(done.path("fulfilmentNote").asString()).contains("AUR-77120");
        assertThat(done.path("history").toString()).contains("CARE:anna.care");
        assertThat(send("POST", "/v1/redemptions/RDM-000003/fulfil", "CARE:anna.care", Map.of("note", "ancora"), 409)
                .path("code").asString()).isEqualTo("REDEMPTION_NOT_FULFILLABLE");
        // Un premio a evasione automatica non si evade a mano.
        assertThat(send("POST", "/v1/redemptions/RDM-000006/fulfil", "CARE:anna.care", Map.of("note", "x"), 409)
                .path("code").asString()).isEqualTo("REDEMPTION_NOT_FULFILLABLE");
    }

    @Test
    void cancelWithRefundRestoresStockAndAsksTheWalletToRefund() throws Exception {
        int stock = reward("RWD-SMART-PLUG").path("stockRemaining").asInt();
        String id = request("MBR-000005", "RWD-SMART-PLUG", ADDRESS, 202).path("redemptionId").asString();
        publishWalletFact(awaitFact("io.loyaltyhub.fact.reward.redemption.requested", id), "io.loyaltyhub.fact.wallet.points.spent",
                Map.of("ledgerEntryId", "LE-" + id, "currency", "PTS", "amount", 6000, "balanceAfter", 22750, "redemptionId", id));
        awaitStatus(id, "CONFIRMED");

        assertThat(send("POST", "/v1/redemptions/" + id + "/cancel", "CARE:anna.care", Map.of("reason", ""), 422)
                .path("code").asString()).isEqualTo("REASON_REQUIRED");
        JsonNode cancelled = send("POST", "/v1/redemptions/" + id + "/cancel", "CARE:anna.care",
                Map.of("reason", "Il membro ha cambiato idea"), 200);
        assertThat(cancelled.path("status").asString()).isEqualTo("CANCELLED");
        assertThat(reward("RWD-SMART-PLUG").path("stockRemaining").asInt()).isEqualTo(stock);

        JsonNode fact = null;
        long deadline = System.currentTimeMillis() + 10_000;
        while (fact == null && System.currentTimeMillis() < deadline) {
            JsonNode f = awaitFact("io.loyaltyhub.fact.reward.redemption.cancelled", id);
            if (f.path("data").path("refund").asBoolean()) {
                fact = f;
            }
        }
        assertThat(fact).isNotNull();
        assertThat(fact.path("lhactor").asString()).isEqualTo("CARE:anna.care");
        assertThat(send("POST", "/v1/redemptions/" + id + "/cancel", "CARE:anna.care", Map.of("reason", "di nuovo"), 409)
                .path("code").asString()).isEqualTo("REDEMPTION_NOT_CANCELLABLE");
        assertThat(send("POST", "/v1/redemptions/RDM-000001/cancel", "ADMIN:test", Map.of("reason", "evasa"), 409)
                .path("code").asString()).as("una richiesta evasa non si annulla").isEqualTo("REDEMPTION_NOT_CANCELLABLE");
    }

    @Test
    void retryFulfilmentIssuesTheCouponOfARequestNeedingAttention() {
        assertThat(get("/v1/redemptions?needsAttention=true").path("items").toString()).contains("RDM-000006");
        JsonNode done = send("POST", "/v1/redemptions/RDM-000006/retry-fulfilment", "ADMIN:test", null, 200);
        assertThat(done.path("status").asString()).isEqualTo("FULFILLED");
        assertThat(done.path("couponCode").asString()).startsWith("SHP25-");
        assertThat(done.path("needsAttention").asBoolean()).isFalse();
        assertThat(send("POST", "/v1/redemptions/RDM-000006/retry-fulfilment", "ADMIN:test", null, 409)
                .path("code").asString()).isEqualTo("REDEMPTION_NOT_RETRYABLE");
    }

    // ---------- helper ----------

    private JsonNode request(String memberId, String rewardCode, Object shipping, int expected) {
        Map<String, Object> body = shipping == null
                ? Map.of("memberId", memberId, "rewardCode", rewardCode)
                : Map.of("memberId", memberId, "rewardCode", rewardCode, "shipping", shipping);
        return send("POST", "/v1/portal/redemptions", "ANALYST:portal", body, expected);
    }

    private int status(String memberId, String rewardCode) {
        return RestClient.create("http://localhost:" + port).post().uri("/v1/portal/redemptions")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("memberId", memberId, "rewardCode", rewardCode))
                .exchange((req, res) -> res.getStatusCode().value());
    }

    private JsonNode reward(String code) {
        for (JsonNode r : get("/v1/rewards?q=" + code)) {
            if (r.path("code").asString().equals(code)) {
                return r;
            }
        }
        throw new AssertionError("premio assente: " + code);
    }

    private JsonNode awaitStatus(String id, String status) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        JsonNode r = null;
        while (System.currentTimeMillis() < deadline) {
            r = get("/v1/portal/redemptions/" + id);
            if (r.path("status").asString().equals(status)) {
                return r;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("richiesta " + id + " non " + status + ": " + r);
    }

    private JsonNode awaitFact(String type, String redemptionId) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "group.id", "it-" + System.nanoTime(), "auto.offset.reset", "earliest",
                "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class))) {
            consumer.subscribe(List.of("lh.facts.v1"));
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> rec : consumer.poll(Duration.ofMillis(300))) {
                    JsonNode e = mapper.readTree(rec.value());
                    if (e.path("type").asString().equals(type) && e.path("data").path("redemptionId").asString().equals(redemptionId)) {
                        return e;
                    }
                }
            }
        }
        throw new AssertionError("nessun " + type + " per " + redemptionId);
    }

    /** Pubblica un fatto del wallet come figlio di {@code parent} (stessa correlazione), come farebbe il wallet vero. */
    private String publishWalletFact(JsonNode parent, String type, Map<String, Object> data) throws Exception {
        String id = "01WALLET" + System.nanoTime();
        Map<String, Object> event = Map.of("specversion", "1.0", "id", id, "source", "urn:loyaltyhub:service:wallet",
                "type", type, "subject", parent.path("subject").asString(), "time", Instant.now().toString(),
                "lhcorrelationid", parent.path("lhcorrelationid").asString(), "lhcausationid", parent.path("id").asString(),
                "lhhop", 0, "data", data);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class))) {
            producer.send(new ProducerRecord<>("lh.facts.v1", parent.path("subject").asString().replace("member:", ""),
                    mapper.writeValueAsString(event))).get();
        }
        return id;
    }

    private JsonNode get(String path) {
        return RestClient.create("http://localhost:" + port).get().uri(path).retrieve().body(JsonNode.class);
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
