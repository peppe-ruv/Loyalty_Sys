package io.loyaltyhub.hub;

import io.loyaltyhub.ingestion.domain.ScenarioTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import tools.jackson.databind.JsonNode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova end-to-end del deployable <strong>consolidato</strong> (docs/13 ADR-023): i 4 servizi girano in un
 * solo JVM e il core loop funziona per intero — {@code POST /v1/events} → campaign valuta → wallet accredita
 * col moltiplicatore di tier → saldo del portale aggiornato. Più i criteri di accettazione M3 che attraversano più
 * servizi (docs/12): {@code SCN-TIER-UP} col ponte interno e il job scadenze. Senza Docker: EmbeddedKafka + Zonky.
 */
@SpringBootTest(
        classes = HubApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.name=hub")
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HubEndToEndIT {

    private static final EmbeddedPostgres PG = startPg();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        // Un solo database, search_path con i 4 schemi (ADR-023).
        registry.add("spring.datasource.url", () -> base + "&currentSchema=ingestion,member,campaign,wallet,insight,reward,gamification");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    void purchaseTravelsThroughAllServicesAndCreditsPoints() {
        // MBR-000002 (Marco) è SILVER (×1,25) e resta SILVER (1 420 + 130 STS < 3 000): acquisto 130 € → +162 PTS.
        long before = walletPts("MBR-000002");
        assertThat(before).as("il wallet è seminato dal profilo demo").isGreaterThan(0);

        // Ultimo giorno feriale alle 10:00: niente moltiplicatore weekend e sempre dentro la finestra dei 30 giorni.
        String weekday = ScenarioTime.resolve("@lastWeekdayT10:00", Instant.now()).toString();
        long scoreBefore = monthScore("MBR-000002", weekday);
        Map<String, Object> event = Map.of(
                "specversion", "1.0", "id", "hub-e2e-01", "source", "urn:loyaltyhub:source:ecommerce",
                "type", "purchase.completed", "subject", "member:MBR-000002", "time", weekday,
                "data", Map.of("orderId", "ORD-HUB-1", "amount", 130, "currency", "EUR", "channel", "ONLINE"));
        JsonNode accepted = client().post().uri("/v1/events")
                .contentType(MediaType.APPLICATION_JSON).body(event).retrieve().body(JsonNode.class);
        assertThat(accepted.path("status").asString()).isEqualTo("ACCEPTED");

        assertThat(awaitPts("MBR-000002", before + 162))
                .as("acquisto 130€ SILVER → +162 PTS attraverso ingestion→campaign→wallet").isEqualTo(before + 162);

        // M5.5: wallet.points.earned alimenta la classifica del mese dell'accredito (+162 rispetto a prima dell'acquisto).
        long expected = scoreBefore + 162;
        long deadline = System.currentTimeMillis() + 15_000;
        long score = monthScore("MBR-000002", weekday);
        while (score != expected && System.currentTimeMillis() < deadline) {
            sleep();
            score = monthScore("MBR-000002", weekday);
        }
        assertThat(score).as("classifica PTS del mese").isEqualTo(expected);
    }

    /** Punteggio del membro in LDB-MONTH-PTS nel mese (Europe/Rome) dell'istante indicato; 0 se assente. */
    private long monthScore(String memberId, String at) {
        LocalDate day = LocalDate.ofInstant(Instant.parse(at), ZoneId.of("Europe/Rome"));
        String periodKey = String.format("%d-%02d", day.getYear(), day.getMonthValue());
        JsonNode ranking = client().get().uri("/v1/leaderboards/LDB-MONTH-PTS/ranking?limit=100&periodKey=" + periodKey)
                .retrieve().body(JsonNode.class);
        for (JsonNode i : ranking.path("items")) {
            if (memberId.equals(i.path("memberId").asString())) {
                return i.path("score").asLong();
            }
        }
        return 0;
    }

    // ---------- accettazione M3 (docs/12) ----------

    @Test
    void scnTierUpBridgesTheUpgradeIntoTheBonusInOneTrace() {
        // Giulia (MBR-000003) SILVER con 2 880 STS: 130 € → +162 PTS e +130 STS → GOLD → tier.upgraded rientra come
        // azione interna (lhhop 1) → CMP-TIER-UP-BONUS +500 PTS. Dalla M5.4 lo stesso acquisto è il terzo del mese
        // (seed 2/3): ACH-3-PURCHASES-MONTH → badge BDG-TRIS → badge.awarded dal ponte → CMP-BADGE-BONUS +100 PTS
        // ("doppia soddisfazione", docs/10 §6). Tutto nello stesso tracciato.
        long before = walletPts("MBR-000003");
        JsonNode started = client().post().uri("/v1/demo/scenarios/SCN-TIER-UP/run")
                .header("X-LH-Actor", "ADMIN:test").retrieve().body(JsonNode.class);
        JsonNode run = awaitRunDone(started.path("runId").asString());
        JsonNode step = run.path("results").get(0);
        assertThat(step.path("status").asString()).isEqualTo("ACCEPTED");
        String correlationId = step.path("correlationId").asString();

        // Il saldo può ricevere anche accrediti di altri test dello stesso contesto (es. rilascio di punti in attesa
        // dal job): l'importo esatto dello scenario si verifica sul suo tracciato, qui sotto.
        assertThat(awaitPtsAtLeast("MBR-000003", before + 162 + 500 + 100)).as("acquisto + bonus di livello + bonus badge")
                .isGreaterThanOrEqualTo(before + 762);
        JsonNode wallet = client().get().uri("/v1/portal/wallets/MBR-000003").retrieve().body(JsonNode.class);
        assertThat(wallet.path("tier").path("code").asString()).isEqualTo("GOLD");

        JsonNode trace = awaitTrace(correlationId, t -> t.path("outcome").path("points").toString().contains("762"));
        assertThat(trace.path("nodes").toString()).contains("achievement.completed", "badge.awarded");
        assertThat(client().get().uri("/v1/portal/badges?memberId=MBR-000003").retrieve().body(JsonNode.class).toString())
                .contains("BDG-TRIS");
        long roots = 0;
        boolean bridgedAction = false;
        for (JsonNode n : trace.path("nodes")) {
            if (n.path("parentEventId").isNull() || n.path("parentEventId").isMissingNode()) {
                roots++;
            }
            if (n.path("family").asString().equalsIgnoreCase("action") && n.path("shortType").asString().equals("tier.upgraded")) {
                bridgedAction = true;
            }
        }
        assertThat(roots).as("un solo albero").isEqualTo(1);
        assertThat(bridgedAction).as("azione tier.upgraded dal ponte nello stesso tracciato").isTrue();
        assertThat(trace.path("outcome").path("tierChange").path("to").asString()).isEqualTo("GOLD");
    }

    @Test
    void expiryJobAtPlus31DaysExpiresChiarasPoints() {
        // Chiara (MBR-000007): 1 900 PTS in scadenza entro 30 giorni (docs/10). Job con asOf = oggi + 31 giorni.
        long before = walletPts("MBR-000007");
        String asOf = LocalDate.now(ZoneId.of("Europe/Rome")).plusDays(31).toString();
        client().post().uri("/v1/demo/jobs/expire-points?asOf=" + asOf)
                .header("X-LH-Actor", "ADMIN:test").retrieve().body(JsonNode.class);
        assertThat(walletPts("MBR-000007")).isEqualTo(before - 1900);

        JsonNode ledger = client().get().uri("/v1/wallets/MBR-000007/ledger").retrieve().body(JsonNode.class);
        assertThat(ledger.toString()).contains("EXPIRE").contains("1900");
    }

    @Test
    void demoResetIsExposedForAllServices() {
        JsonNode body = client().post().uri("/v1/demo/reset").header("X-LH-Actor", "ADMIN:test")
                .retrieve().body(JsonNode.class);
        assertThat(body.path("status").asString()).isEqualTo("OK");
        assertThat(body.path("reset").size()).as("tutti i seeder dei 4 servizi").isGreaterThanOrEqualTo(4);
    }

    @Test
    void rewardCatalogIsServedByTheHubWithItsOwnSnapshot() {
        // Schema reward nel search_path condiviso: lo snapshot dei membri è reward_member_snapshot, non quello di
        // campaign. Marco (SILVER) vede il weekend (F5) bloccato per tier.
        JsonNode catalog = client().get().uri("/v1/portal/catalog?memberId=MBR-000002").retrieve().body(JsonNode.class);
        assertThat(catalog.path("bands").size()).isEqualTo(5);
        assertThat(catalog.toString()).contains("RWD-WEEKEND").contains("lockedByTier");
    }

    // ---------- saga di richiesta premio (M4.3, reward-service.md §7) ----------

    @Test
    void davideRedeemsShop10AndTheSagaIsOneTrace() {
        // Davide (MBR-000004, 12 300 PTS) richiede RWD-SHOP-10 (1 500 PTS) → 202; entro 10 s FULFILLED con coupon,
        // saldo −1 500, stock −1. Richiesta → spesa → conferma → coupon → evasione nello stesso tracciato.
        long before = walletPts("MBR-000004");
        int stock = stockOf("RWD-SHOP-10");
        JsonNode accepted = client().post().uri("/v1/portal/redemptions").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("memberId", "MBR-000004", "rewardCode", "RWD-SHOP-10")).retrieve().body(JsonNode.class);
        String id = accepted.path("redemptionId").asString();

        JsonNode done = awaitRedemption(id, "FULFILLED", 10_000);
        assertThat(done.path("couponCode").asString()).startsWith("SHP10-");
        assertThat(walletPts("MBR-000004")).isEqualTo(before - 1500);
        assertThat(stockOf("RWD-SHOP-10")).isEqualTo(stock - 1);

        // M4.6: la conferma rientra dal ponte interno come azione reward.redeemed (lhhop 1), nello stesso albero.
        JsonNode trace = awaitTrace(accepted.path("correlationId").asString(),
                t -> t.toString().contains("reward.redemption.fulfilled") && t.toString().contains("\"reward.redeemed\""));
        String nodes = trace.path("nodes").toString();
        assertThat(nodes).contains("reward.redemption.requested", "wallet.points.spent", "reward.redemption.confirmed",
                "coupon.issued", "reward.redemption.fulfilled");
        long roots = 0;
        boolean bridged = false;
        for (JsonNode n : trace.path("nodes")) {
            if (n.path("parentEventId").isNull() || n.path("parentEventId").isMissingNode()) {
                roots++;
            }
            if (n.path("family").asString().equalsIgnoreCase("action") && n.path("shortType").asString().equals("reward.redeemed")) {
                bridged = true;
            }
        }
        assertThat(roots).as("un solo albero").isEqualTo(1);
        assertThat(bridged).as("azione interna reward.redeemed dal ponte (M4.6)").isTrue();
    }

    @Test
    void annaWithoutPointsIsRejectedAndStockIsUnchanged() {
        // Anna (MBR-000001, 100 PTS) richiede RWD-COFFEE-5 (500 PTS) → 202, poi REJECTED (INSUFFICIENT_BALANCE).
        int stock = stockOf("RWD-COFFEE-5");
        JsonNode accepted = client().post().uri("/v1/portal/redemptions").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("memberId", "MBR-000001", "rewardCode", "RWD-COFFEE-5")).retrieve().body(JsonNode.class);
        JsonNode r = awaitRedemption(accepted.path("redemptionId").asString(), "REJECTED", 10_000);
        assertThat(r.path("rejectReason").asString()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(stockOf("RWD-COFFEE-5")).isEqualTo(stock);
        assertThat(walletPts("MBR-000001")).isEqualTo(100);
    }

    @Test
    void cancellingSofiasSeededRequestRefundsHerPoints() {
        // RDM-000003: Sofia (MBR-000011), RWD-BORRACCIA CONFIRMED in attesa di spedizione, spesa seminata nel wallet.
        long before = walletPts("MBR-000011");
        int stock = stockOf("RWD-BORRACCIA");
        JsonNode cancelled = client().post().uri("/v1/redemptions/RDM-000003/cancel")
                .header("X-LH-Actor", "CARE:anna.care").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reason", "Articolo danneggiato in magazzino")).retrieve().body(JsonNode.class);
        assertThat(cancelled.path("status").asString()).isEqualTo("CANCELLED");
        assertThat(awaitPts("MBR-000011", before + 1500)).as("rimborso dal wallet").isEqualTo(before + 1500);
        assertThat(stockOf("RWD-BORRACCIA")).as("stock ripristinato").isEqualTo(stock + 1);

        JsonNode lots = client().get().uri("/v1/wallets/MBR-000011/lots").retrieve().body(JsonNode.class);
        long sum = 0;
        for (JsonNode l : lots) {
            if (l.path("currency").asString().equals("PTS") && l.path("status").asString().equals("ACTIVE")) {
                sum += l.path("remaining").asLong();
            }
        }
        assertThat(sum).as("Σ lotti attivi = saldo").isEqualTo(before + 1500);
    }

    // ---------- helper ----------

    @Test
    void digitalAchievementAwardsABadgeThatTheBonusCampaignRewards() {
        // M5.4 (docs/servizi/gamification-service.md §7): ebill.activated + directdebit.activated → ACH-DIGITAL completato,
        // badge BDG-DIGITAL, badge.awarded rientra come azione e CMP-BADGE-BONUS accredita 100 PTS.
        String weekday = ScenarioTime.resolve("@lastWeekdayT11:00", Instant.now()).toString();
        for (String type : new String[]{"ebill.activated", "directdebit.activated"}) {
            Map<String, Object> event = Map.of("specversion", "1.0", "id", "hub-digital-" + type, "source", "urn:loyaltyhub:source:billing",
                    "type", type, "subject", "member:MBR-000002", "time", weekday, "data", Map.of("contractId", "CTR-HUB-1"));
            client().post().uri("/v1/events").contentType(MediaType.APPLICATION_JSON).body(event).retrieve().body(JsonNode.class);
        }
        long deadline = System.currentTimeMillis() + 30_000;
        boolean bonus = false;
        while (!bonus && System.currentTimeMillis() < deadline) {
            JsonNode ledger = client().get().uri("/v1/wallets/MBR-000002/ledger").retrieve().body(JsonNode.class);
            for (JsonNode e : ledger.path("items").isMissingNode() ? ledger : ledger.path("items")) {
                if ("CMP-BADGE-BONUS".equals(e.path("campaignCode").asString()) && e.path("amount").asLong() == 100) {
                    bonus = true;
                }
            }
            if (!bonus) {
                sleep();
            }
        }
        assertThat(bonus).as("CMP-BADGE-BONUS +100 PTS dal badge.awarded").isTrue();
        assertThat(client().get().uri("/v1/portal/badges?memberId=MBR-000002").retrieve().body(JsonNode.class).toString())
                .contains("BDG-DIGITAL");
    }

    @Test
    void instantWinPrizesAreDeliveredThroughTheBridge() {
        // M5.3 (docs/03 §6, docs/servizi/gamification-service.md §7): giocata → contest.won → ponte → action.instantwin.won
        // → campaign.evaluated → points.grant → wallet.points.earned, un solo tracciato. Istanti preparati dal test
        // (M5.7 aggiunge "pianta un istante"): tutti nel futuro tranne uno, già passato, del premio voluto.
        long before = walletPts("MBR-000009");
        onlyOpenInstant("PTS-50");
        JsonNode win = play("MBR-000009");
        assertThat(win.path("outcome").asString()).isEqualTo("WIN");
        assertThat(win.path("prize").path("code").asString()).isEqualTo("PTS-50");
        assertThat(awaitPts("MBR-000009", before + 50)).as("50 punti vinti, senza moltiplicatore di livello").isEqualTo(before + 50);

        JsonNode trace = awaitTrace(win.path("correlationId").asString(), t -> t.toString().contains("wallet.points.earned"));
        String nodes = trace.path("nodes").toString();
        assertThat(nodes).contains("contest.played", "contest.won", "instantwin.won", "campaign.evaluated", "points.grant",
                "wallet.points.earned");
        long roots = 0;
        for (JsonNode n : trace.path("nodes")) {
            if (n.path("parentEventId").isNull() || n.path("parentEventId").isMissingNode()) {
                roots++;
            }
        }
        assertThat(roots).as("un solo albero").isEqualTo(1);

        // Premio coupon: CMP-IW-PRIZE-COUPON → effetto coupon.issue → reward emette un codice del pool del premio.
        onlyOpenInstant("COFFEE");
        JsonNode coupon = play("MBR-000006");
        assertThat(coupon.path("prize").path("rewardCode").asString()).isEqualTo("RWD-COFFEE-5");
        long deadline = System.currentTimeMillis() + 30_000;
        JsonNode issued = null;
        while (issued == null && System.currentTimeMillis() < deadline) {
            for (JsonNode c : client().get().uri("/v1/portal/coupons?memberId=MBR-000006").retrieve().body(JsonNode.class)) {
                if ("RWD-COFFEE-5".equals(c.path("rewardCode").asString()) && "CAMPAIGN".equals(c.path("origin").asString())) {
                    issued = c;
                }
            }
            if (issued == null) {
                sleep();
            }
        }
        assertThat(issued).as("coupon della vincita emesso a Stefano").isNotNull();
        assertThat(issued.path("code").asString()).startsWith("CAF-");
        assertThat(issued.path("status").asString()).isEqualTo("ISSUED");
    }

    private void onlyOpenInstant(String prizeCode) {
        String contestId = jdbc.sql("SELECT id FROM contest WHERE code = 'IW-AUTUNNO'").query(String.class).single();
        jdbc.sql("UPDATE winning_instant SET instant_at = now() + interval '30 days' WHERE contest_id = ? AND status = 'OPEN'")
                .param(contestId).update();
        int moved = jdbc.sql("""
                        UPDATE winning_instant SET instant_at = now() - interval '1 second'
                        WHERE id = (SELECT w.id FROM winning_instant w JOIN prize p ON p.id = w.prize_id
                                    WHERE w.contest_id = ? AND w.status = 'OPEN' AND p.code = ? ORDER BY w.id LIMIT 1)
                        """)
                .params(contestId, prizeCode).update();
        assertThat(moved).isEqualTo(1);
    }

    private JsonNode play(String memberId) {
        return client().post().uri("/v1/portal/contests/IW-AUTUNNO/play").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("memberId", memberId)).retrieve().body(JsonNode.class);
    }

    private JsonNode awaitRedemption(String id, String status, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        JsonNode r = null;
        while (System.currentTimeMillis() < deadline) {
            r = client().get().uri("/v1/portal/redemptions/" + id).retrieve().body(JsonNode.class);
            if (r.path("status").asString().equals(status)) {
                return r;
            }
            sleep();
        }
        throw new AssertionError("richiesta " + id + " non " + status + " entro " + timeoutMs + " ms: " + r);
    }

    private int stockOf(String rewardCode) {
        for (JsonNode r : client().get().uri("/v1/rewards?q=" + rewardCode).retrieve().body(JsonNode.class)) {
            if (r.path("code").asString().equals(rewardCode)) {
                return r.path("stockRemaining").asInt();
            }
        }
        throw new AssertionError("premio assente: " + rewardCode);
    }

    private long awaitPtsAtLeast(String memberId, long min) {
        long deadline = System.currentTimeMillis() + 30_000;
        long value = walletPts(memberId);
        while (value < min && System.currentTimeMillis() < deadline) {
            sleep();
            value = walletPts(memberId);
        }
        return value;
    }

    private long awaitPts(String memberId, long expected) {
        long deadline = System.currentTimeMillis() + 30_000;
        long value = walletPts(memberId);
        while (value != expected && System.currentTimeMillis() < deadline) {
            sleep();
            value = walletPts(memberId);
        }
        return value;
    }

    private JsonNode awaitRunDone(String runId) {
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode run = null;
        while (System.currentTimeMillis() < deadline) {
            run = client().get().uri("/v1/demo/scenario-runs/" + runId).retrieve().body(JsonNode.class);
            if (!run.path("status").asString().equals("RUNNING")) {
                return run;
            }
            sleep();
        }
        return run;
    }

    private JsonNode awaitTrace(String correlationId, java.util.function.Predicate<JsonNode> done) {
        long deadline = System.currentTimeMillis() + 30_000;
        JsonNode trace = null;
        while (System.currentTimeMillis() < deadline) {
            trace = client().get().uri("/v1/traces/" + correlationId)
                    .exchange((req, res) -> res.getStatusCode().value() == 200 ? new tools.jackson.databind.ObjectMapper().readTree(res.getBody()) : null);
            if (trace != null && done.test(trace)) {
                return trace;
            }
            sleep();
        }
        assertThat(trace).as("tracciato " + correlationId).isNotNull();
        return trace;
    }

    private long walletPts(String memberId) {
        JsonNode w = client().get().uri("/v1/portal/wallets/" + memberId).retrieve().body(JsonNode.class);
        return w.path("balances").path("PTS").path("active").asLong();
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private static void sleep() {
        try {
            Thread.sleep(500);
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
