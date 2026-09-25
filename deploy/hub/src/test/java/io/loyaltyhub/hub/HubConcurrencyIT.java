package io.loyaltyhub.hub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = HubApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.name=hub")
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HubConcurrencyIT {

    private static final EmbeddedPostgres PG = startPg();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper mapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=ingestion,member,campaign,wallet,insight,reward,gamification,engagement");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    void doubleSpendIsPrevented() throws Exception {
        String memberId = "MBR-000004";
        long ptsBefore = walletPts(memberId);

        long deadlineReward = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadlineReward) {
            try {
                client().get().uri("/v1/portal/catalog?memberId=" + memberId).header("X-LH-Actor", "ANALYST:portal").retrieve().toBodilessEntity();
                break;
            } catch (Exception e) {
                sleep(300);
            }
        }

        setBalance(memberId, 1500);

        String rewardCode = "RWD-CONC-DS-" + UUID.randomUUID().toString().substring(0, 8);
        createAndApproveReward(rewardCode, 500, 100);

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        List<Future<Integer>> results = new ArrayList<>();
        List<String> redemptionIds = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            results.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    JsonNode r = client().post().uri("/v1/portal/redemptions")
                            .header("X-LH-Actor", "ANALYST:portal")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("memberId", memberId, "rewardCode", rewardCode))
                            .retrieve().body(JsonNode.class);
                    if (r.has("id")) {
                        redemptionIds.add(r.path("id").asString());
                    }
                    return 202;
                } catch (HttpClientErrorException e) {
                    return e.getStatusCode().value();
                } finally {
                    done.countDown();
                }
            }));
        }

        ready.await();
        start.countDown();
        done.await(20, TimeUnit.SECONDS);

        long deadline = System.currentTimeMillis() + 30_000;
        List<JsonNode> redemptions = new ArrayList<>();
        while (System.currentTimeMillis() < deadline) {
            redemptions.clear();
            boolean allFinal = true;
            for (String id : redemptionIds) {
                try {
                    JsonNode r = client().get().uri("/v1/portal/redemptions/" + id + "?memberId=" + memberId).header("X-LH-Actor", "ANALYST:portal").retrieve().body(JsonNode.class);
                    redemptions.add(r);
                    String status = r.path("status").asString();
                    if (status.equals("PENDING") || status.equals("CONFIRMING")) {
                        allFinal = false;
                    }
                } catch (Exception e) {}
            }
            if (allFinal && redemptions.size() == redemptionIds.size()) break;
            sleep(300);
        }

        if (System.currentTimeMillis() >= deadline) org.assertj.core.api.Assertions.fail("Polling redemptions timeout out. Last state: " + redemptions);

        int successful = 0;
        for (JsonNode r : redemptions) {
            String status = r.path("status").asString();
            if (status.equals("CONFIRMED") || status.equals("FULFILLED")) {
                successful++;
            } else {
                assertThat(status).isEqualTo("REJECTED");
            }
        }

        assertThat(successful).as("exactly 3 successful redemptions").isEqualTo(3);

        long activePts = walletPts(memberId);
        assertThat(activePts).isGreaterThanOrEqualTo(0);

        JsonNode activity = client().get().uri("/v1/portal/wallets/" + memberId + "/activity?size=100").header("X-LH-Actor", "ANALYST:portal").retrieve().body(JsonNode.class);
        long totalSpent = 0;
        for (JsonNode item : activity) {
            if ("Premio richiesto".equals(item.path("title").asString()) && item.path("id").asString().startsWith("MOV")) {
                long amount = Math.abs(item.path("amount").asLong());
                if (amount == 500) {
                    totalSpent += amount;
                }
            }
        }

        assertThat(totalSpent).as("sum of spent movements == 3 * cost").isEqualTo(1500);
        JsonNode wallet = client().get().uri("/v1/portal/wallets/" + memberId).header("X-LH-Actor", "ANALYST:portal").retrieve().body(JsonNode.class);
        long sumLots = 0;
        for (JsonNode lot : wallet.path("balances").path("PTS").path("lots")) {
            sumLots += lot.path("remaining").asLong();
        }
        assertThat(sumLots).as("lots remaining equals active balance").isEqualTo(activePts);
    }

    @Test
    void stockRaceWithRefund() throws Exception {
        String rewardCode = "RWD-CONC-SR-" + UUID.randomUUID().toString().substring(0, 8);
        createAndApproveReward(rewardCode, 100, 5);

        int threads = 20;
        List<String> memberIds = new ArrayList<>();
        String[] seedMembers = {
            "MBR-000004", "MBR-000005", "MBR-000006", "MBR-000010", "MBR-000011",
            "MBR-000027", "MBR-000013", "MBR-000014", "MBR-000015", "MBR-000016",
            "MBR-000017", "MBR-000018", "MBR-000019", "MBR-000020", "MBR-000021",
            "MBR-000022", "MBR-000023", "MBR-000024", "MBR-000025", "MBR-000026"
        };

        for (int i = 0; i < threads; i++) {
            String mid = seedMembers[i];

            long deadlineReward = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadlineReward) {
                try {
                    client().get().uri("/v1/portal/catalog?memberId=" + mid).header("X-LH-Actor", "ANALYST:portal").retrieve().toBodilessEntity();
                    break;
                } catch (Exception e) {
                    sleep(300);
                }
            }

            setBalance(mid, 200);
            memberIds.add(mid);
        }

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        List<Future<String>> results = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final String mid = memberIds.get(i);
            results.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    JsonNode r = client().post().uri("/v1/portal/redemptions")
                            .header("X-LH-Actor", "ANALYST:portal")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("memberId", mid, "rewardCode", rewardCode))
                            .retrieve().body(JsonNode.class);
                    if (r.has("id")) return r.path("id").asString();
                    return null;
                } catch (HttpClientErrorException e) {
                    return null;
                } finally {
                    done.countDown();
                }
            }));
        }

        ready.await();
        start.countDown();
        done.await(20, TimeUnit.SECONDS);

        long deadline = System.currentTimeMillis() + 30_000;
        int successful = 0;
        String aSuccessfulRedemptionId = null;
        String successfulMemberId = null;

        while (System.currentTimeMillis() < deadline) {
            successful = 0;
            boolean allFinal = true;
            for (String mid : memberIds) {
                try {
                    JsonNode list = client().get().uri("/v1/portal/redemptions?memberId=" + mid).header("X-LH-Actor", "ANALYST:portal").retrieve().body(JsonNode.class);
                    for (JsonNode r : list) {
                        if (rewardCode.equals(r.path("rewardCode").asString())) {
                            String status = r.path("status").asString();
                            if (status.equals("PENDING") || status.equals("CONFIRMING")) allFinal = false;
                            if (status.equals("CONFIRMED") || status.equals("FULFILLED")) {
                                successful++;
                                if (aSuccessfulRedemptionId == null) {
                                    aSuccessfulRedemptionId = r.path("id").asString();
                                    successfulMemberId = mid;
                                }
                            }
                        }
                    }
                } catch (Exception e) {}
            }
            if (allFinal) break;
            sleep(500);
        }

        if (System.currentTimeMillis() >= deadline) org.assertj.core.api.Assertions.fail("Polling redemptions timeout out");

        assertThat(successful).as("exactly 5 succeed").isEqualTo(5);
        int remainingStock = stockOf(rewardCode);
        assertThat(remainingStock).as("stock remaining is 0").isEqualTo(0);

        assertThat(aSuccessfulRedemptionId).isNotNull();
        long ptsBeforeRefund = walletPts(successfulMemberId);

        client().post().uri("/v1/redemptions/" + aSuccessfulRedemptionId + "/cancel")
                .header("X-LH-Actor", "CARE:anna.care")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reason", "Concurrency Test Refund"))
                .retrieve().toBodilessEntity();

        deadline = System.currentTimeMillis() + 10_000;
        boolean cancelled = false;
        while (System.currentTimeMillis() < deadline) {
            try {
                JsonNode r = client().get().uri("/v1/portal/redemptions/" + aSuccessfulRedemptionId).header("X-LH-Actor", "ANALYST:portal").retrieve().body(JsonNode.class);
                if ("CANCELLED".equals(r.path("status").asString())) {
                    cancelled = true;
                    break;
                }
            } catch (Exception e) {}
            sleep(300);
        }
        assertThat(cancelled).as("redemption was cancelled").isTrue();

        assertThat(stockOf(rewardCode)).as("stock restored once").isEqualTo(1);
        long ptsAfterRefund = walletPts(successfulMemberId);
        assertThat(ptsAfterRefund).as("points restored").isEqualTo(ptsBeforeRefund + 100);
    }

    @Test
    void instantWinRace() throws Exception {
        String contestCode = "IW-AUTUNNO";
        String prizeCode = "PTS-100";
        JsonNode planted = client().post().uri("/v1/demo/contests/" + contestCode + "/plant-instant")
                .header("X-LH-Actor", "ADMIN:test").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("prizeCode", prizeCode)).retrieve().body(JsonNode.class);
        String instantId = planted.path("id").asText();
        if (instantId == null || instantId.isEmpty() || !instantId.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) {
            instantId = UUID.randomUUID().toString();
        }
        assertThat(instantId).isNotNull();

        String contestId = jdbc.sql("SELECT id FROM contest WHERE code = ?").param(contestCode).query(String.class).single();
        jdbc.sql("UPDATE winning_instant SET instant_at = now() - interval '1 second' WHERE contest_id = ? AND status = 'OPEN' AND id = ?")
                .param(contestId).param(instantId).update();
        jdbc.sql("UPDATE winning_instant SET instant_at = now() + interval '30 days' WHERE contest_id = ? AND status = 'OPEN' AND id != ?")
                .param(contestId).param(instantId).update();

        int threads = 20;
        List<String> memberIds = new ArrayList<>();
        String[] seedMembers = {
            "MBR-000004", "MBR-000005", "MBR-000006", "MBR-000010", "MBR-000011",
            "MBR-000027", "MBR-000013", "MBR-000014", "MBR-000015", "MBR-000016",
            "MBR-000017", "MBR-000018", "MBR-000019", "MBR-000020", "MBR-000021",
            "MBR-000022", "MBR-000023", "MBR-000024", "MBR-000025", "MBR-000026"
        };
        for (int i = 0; i < threads; i++) {
            memberIds.add(seedMembers[i]);
        }

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        List<Future<JsonNode>> results = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final String mid = memberIds.get(i);
            results.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    return client().post().uri("/v1/portal/contests/" + contestCode + "/play")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("memberId", mid))
                            .retrieve().body(JsonNode.class);
                } catch (Exception e) {
                    return null;
                } finally {
                    done.countDown();
                }
            }));
        }

        ready.await();
        start.countDown();
        done.await(20, TimeUnit.SECONDS);

        int wins = 0;
        int plays = 0;
        for (Future<JsonNode> f : results) {
            JsonNode r = f.get();
            if (r != null) {
                plays++;
                if ("WIN".equals(r.path("outcome").asString()) && prizeCode.equals(r.path("prize").path("code").asString())) {
                    wins++;
                }
            }
        }

        assertThat(wins).as("only one wins the instant").isEqualTo(1);
        assertThat(plays).as("plays count == requests accepted").isEqualTo(20);

        JsonNode contest = client().get().uri("/v1/contests/" + contestId).header("X-LH-Actor", "ANALYST:sara").retrieve().body(JsonNode.class);
        for (JsonNode p : contest.path("prizes")) {
            if (prizeCode.equals(p.path("code").asString())) {
                assertThat(p.path("remaining").asInt()).as("prizesRemaining never negative").isGreaterThanOrEqualTo(0);
            }
        }
    }

    @Test
    void concurrentEditorSaves() throws Exception {
        String rewardCode = "RWD-CONC-ED-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode created = client().post().uri("/v1/rewards").header("X-LH-Actor", "MARKETING:giulia")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", rewardCode, "name", "Editor Test", "type", "DIGITAL", "category", "CASA", "band", "F1", "fulfilment", "MANUAL", "stockTotal", 10))
                .retrieve().body(JsonNode.class);

        String id = created.path("id").asString();
        long version = created.path("version").asLong();

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        List<Future<Integer>> results = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int index = i;
            results.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    client().put().uri("/v1/rewards/" + id).header("X-LH-Actor", "MARKETING:giulia")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("stockTotal", 20 + index, "version", version))
                            .retrieve().toBodilessEntity();
                    return 200;
                } catch (HttpClientErrorException e) {
                    return e.getStatusCode().value();
                } finally {
                    done.countDown();
                }
            }));
        }

        ready.await();
        start.countDown();
        done.await(20, TimeUnit.SECONDS);

        int successCount = 0;
        int conflictCount = 0;

        for (Future<Integer> f : results) {
            int status = f.get();
            if (status == 200) successCount++;
            else if (status == 409) conflictCount++;
        }

        assertThat(successCount).as("exactly 1 succeeds").isEqualTo(1);
        assertThat(conflictCount).as("9 get 409 VERSION_CONFLICT").isEqualTo(9);
    }

    @Test
    void concurrentInboundMatch() throws Exception {
        String evtId = UUID.randomUUID().toString();
        Map<String, Object> event = Map.of(
                "specversion", "1.0", "id", evtId, "source", "urn:loyaltyhub:source:ecommerce",
                "type", "purchase.completed", "subject", "email:unmatched@example.com", "time", Instant.now().toString(),
                "data", Map.of("orderId", "ORD-CONC", "amount", 100, "currency", "EUR")
        );
        client().post().uri("/v1/events").header("X-LH-Actor", "ADMIN:test").contentType(MediaType.APPLICATION_JSON).body(event).retrieve().toBodilessEntity();

        final String[] rowIdContainer = new String[1];
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode list = client().get().uri("/v1/inbound-events?status=UNMATCHED").header("X-LH-Actor", "CARE:anna.care").retrieve().body(JsonNode.class);
            for (JsonNode n : list) {
                if (evtId.equals(n.path("eventId").asString())) {
                    rowIdContainer[0] = n.path("id").asString();
                    break;
                }
            }
            if (rowIdContainer[0] != null) break;
            sleep(300);
        }
        assertThat(rowIdContainer[0]).isNotNull();
        String rowId = rowIdContainer[0];

        String memberId = "MBR-000004";

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        List<Future<Integer>> results = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            results.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    client().post().uri("/v1/inbound-events/" + rowId + "/match").header("X-LH-Actor", "CARE:anna.care")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("memberId", memberId))
                            .retrieve().toBodilessEntity();
                    return 200;
                } catch (HttpClientErrorException e) {
                    return e.getStatusCode().value();
                } finally {
                    done.countDown();
                }
            }));
        }

        ready.await();
        start.countDown();
        done.await(20, TimeUnit.SECONDS);

        int successCount = 0;
        int conflictCount = 0;

        for (Future<Integer> f : results) {
            int status = f.get();
            if (status == 200) successCount++;
            else if (status == 409) conflictCount++;
        }

        assertThat(successCount).as("exactly 1 succeeds").isEqualTo(1);
        assertThat(conflictCount).as("9 get 409").isEqualTo(9);
    }

    // --- Helper methods ---
    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
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

    private void setBalance(String memberId, long desiredBalance) throws Exception {
        long current = walletPts(memberId);
        long diff = desiredBalance - current;
        if (diff == 0) return;

        String direction = diff > 0 ? "CREDIT" : "DEBIT";
        client().post().uri("/v1/wallets/" + memberId + "/adjustments").header("X-LH-Actor", "ADMIN:marta")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("currency", "PTS", "direction", direction, "amount", Math.abs(diff), "reason", "CORRECTION", "note", "fondi per il test di concorrenza"))
                .retrieve().toBodilessEntity();
    }

    private long walletPts(String memberId) {
        try {
            JsonNode w = client().get().uri("/v1/portal/wallets/" + memberId).header("X-LH-Actor", "ANALYST:portal").retrieve().body(JsonNode.class);
            return w.path("balances").path("PTS").path("active").asLong();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 404) return 0;
            throw e;
        }
    }

    private void createAndApproveReward(String code, int cost, int stock) {
        JsonNode created = client().post().uri("/v1/rewards").header("X-LH-Actor", "MARKETING:giulia")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", code, "name", "Conc Test Reward", "type", "DIGITAL", "category", "CASA", "band", "F1", "fulfilment", "MANUAL", "stockTotal", stock))
                .retrieve().body(JsonNode.class);
        String id = created.path("id").asString();

        String existingBandCode = jdbc.sql("SELECT code FROM reward_band WHERE points_threshold = ?").param(cost).query(String.class).optional().orElse(null);
        if (existingBandCode == null) {
            existingBandCode = "F-CONC-" + cost;
            jdbc.sql("INSERT INTO reward_band (code, name, points_threshold) VALUES (?, ?, ?)")
                    .params(existingBandCode, "Conc Band " + cost, cost).update();
        }

        jdbc.sql("UPDATE reward SET band_code = ? WHERE id = ?")
                .params(existingBandCode, id).update();

        client().post().uri("/v1/rewards/" + id + "/transitions").header("X-LH-Actor", "MARKETING:giulia")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("action", "SUBMIT")).retrieve().toBodilessEntity();
        client().post().uri("/v1/rewards/" + id + "/transitions").header("X-LH-Actor", "LEGAL:elena")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("action", "APPROVE")).retrieve().toBodilessEntity();
        client().post().uri("/v1/rewards/" + id + "/transitions").header("X-LH-Actor", "MARKETING:giulia")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("action", "PUBLISH")).retrieve().toBodilessEntity();

        JsonNode live = client().get().uri("/v1/rewards/" + id).retrieve().body(JsonNode.class);
        assertThat(live.path("status").asString()).isEqualTo("LIVE");
        assertThat(live.path("stockRemaining").asInt()).isEqualTo(stock);
    }

    private int stockOf(String code) {
        JsonNode list = client().get().uri("/v1/rewards?q=" + code).retrieve().body(JsonNode.class);
        for (JsonNode r : list) {
            if (code.equals(r.path("code").asString())) {
                return r.path("stockRemaining").asInt();
            }
        }
        return -1;
    }
}
