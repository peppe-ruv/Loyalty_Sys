package io.loyaltyhub.wallet.testbook;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.inbox.EventRouter;
import io.loyaltyhub.wallet.application.RedemptionPayments;
import io.loyaltyhub.wallet.application.WalletService;
import io.loyaltyhub.wallet.infra.MemberTierRepository;
import io.loyaltyhub.wallet.infra.PointsLotRepository;
import io.loyaltyhub.wallet.infra.WalletRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.loyaltyhub.wallet.testbook.WalItSupport.instant;
import static io.loyaltyhub.wallet.testbook.WalItSupport.num;
import static io.loyaltyhub.wallet.testbook.WalTestbook.MAPPER;
import static io.loyaltyhub.wallet.testbook.WalTestbook.freshId;
import static io.loyaltyhub.wallet.testbook.WalTestbook.freshMember;
import static io.loyaltyhub.wallet.testbook.WalTestbook.grant;
import static io.loyaltyhub.wallet.testbook.WalTestbook.rome;
import static io.loyaltyhub.wallet.testbook.WalTestbook.sts;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-WAL (docs/testbook/TB-WAL-wallet.md) — accrediti, lotti, pending, salita di livello, viste, passività e job
 * (scadenze, preavvisi, rilascio). Servizio reale su Postgres embedded, profilo {@code demo} (livelli, valute ed edizioni
 * dai seed), orologio fisso spostabile, membri freschi per ogni riga. I fatti si leggono dall'outbox, scritta nella
 * stessa transazione del movimento.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TestbookWalAccrualIT {

    /** «Oggi» della demo durante i test: 24 set 2026, 10:00 a Roma. */
    static final Instant NOW = Instant.parse("2026-09-24T08:00:00Z");
    static final WalTestbook.MutableClock CLOCK = new WalTestbook.MutableClock(NOW);
    /** Data di business delle azioni (esempio di docs/05 §2): 18 set 2026, 12:15 a Roma. */
    static final Instant T0 = rome("2026-09-18T12:15:00");
    private static final EmbeddedPostgres PG = startPg();

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        Clock testbookClock() {
            return CLOCK;
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=wallet");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Value("${local.server.port}")
    int port;
    @Autowired
    WalletService wallet;
    @Autowired
    RedemptionPayments payments;
    @Autowired
    @Qualifier("walletEventRouter")
    EventRouter router;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    WalletRepository wallets;
    @Autowired
    MemberTierRepository memberTiers;
    @Autowired
    PointsLotRepository lots;

    WalItSupport s;

    @BeforeAll
    void init() {
        s = new WalItSupport(port, jdbc, wallets, memberTiers, lots);
    }

    @BeforeEach
    void resetClock() {
        CLOCK.set(NOW);
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    // =====================================================================================================
    // §3 Accredito (points.grant): valuta × pendingDays × moltiplicatore × livello, stato del membro, importi
    // =====================================================================================================

    @Order(1)
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/wal/grants.csv", numLinesToSkip = 1)
    void grants(String id, String description, String currency, int pendingDays, boolean applies, String tier,
                String status, long amount, String expected) {
        String m = freshMember(id);
        s.member(m, tier, WalTestbook.threshold(tier), status);
        String effectId = freshId("EFF");

        // Righe 049–051 (membro non ACTIVE) — TESTBOOK: ambiguo, vedi TB-WAL-GRT-049..051
        wallet.applyGrant(grant(m, effectId, currency, amount, applies, pendingDays, T0));

        if ("NONE".equals(expected)) {
            assertThat(s.ledger(m, "EARN")).as("%s: nessun movimento", id).isEmpty();
            assertThat(s.allLots(m)).as("%s: nessun lotto", id).isEmpty();
            assertThat(s.memberFacts("wallet.points.earned", m)).as("%s: nessun fatto", id).isEmpty();
            return;
        }
        long exp = "TIER".equals(expected) ? WalTestbook.floorTimes(amount, tier) : amount;
        boolean pending = pendingDays > 0;

        List<Map<String, Object>> earn = s.ledger(m, "EARN");
        assertThat(earn).as("%s: un movimento EARN", id).hasSize(1);
        Map<String, Object> e = earn.getFirst();
        assertThat(num(e.get("amount"))).as("%s: importo EARN", id).isEqualTo(exp);
        assertThat(e.get("direction")).isEqualTo("+");
        assertThat(instant(e.get("occurred_at"))).as("%s: data di business = time dell'effetto", id).isEqualTo(T0);
        JsonNode meta = MAPPER.readTree(e.get("metadata").toString());
        assertThat(meta.path("baseAmount").asLong()).as("%s: metadata.baseAmount", id).isEqualTo(amount);
        if ("TIER".equals(expected)) {
            assertThat(meta.path("tierCode").asString()).isEqualTo(tier);
            assertThat(meta.path("tierMultiplier").decimalValue()).usingComparator(BigDecimal::compareTo)
                    .isEqualTo(WalTestbook.multiplier(tier));
        }

        List<Map<String, Object>> lotRows = s.allLots(m);
        assertThat(lotRows).as("%s: un lotto", id).hasSize(1);
        Map<String, Object> lot = lotRows.getFirst();
        assertThat(lot.get("status")).as("%s: stato del lotto", id).isEqualTo(pending ? "PENDING" : "ACTIVE");
        assertThat(num(lot.get("amount"))).isEqualTo(exp);
        assertThat(num(lot.get("remaining"))).isEqualTo(exp);
        assertThat(instant(lot.get("earned_at"))).isEqualTo(T0);
        assertThat(instant(lot.get("available_at"))).as("%s: availableAt", id)
                .isEqualTo(pending ? T0.plus(pendingDays, ChronoUnit.DAYS) : null);

        assertThat(s.balance(m, currency).balanceActive()).as("%s: saldo attivo", id).isEqualTo(pending ? 0 : exp);
        assertThat(s.balance(m, currency).balancePending()).as("%s: saldo in attesa", id).isEqualTo(pending ? exp : 0);

        List<JsonNode> facts = s.memberFacts("wallet.points.earned", m);
        assertThat(facts).as("%s: un fatto wallet.points.earned", id).hasSize(1);
        JsonNode d = facts.getFirst().path("data");
        assertThat(d.path("amount").asLong()).isEqualTo(exp);
        assertThat(d.path("currency").asString()).isEqualTo(currency);
        assertThat(d.path("pending").asBoolean()).isEqualTo(pending);
        if ("PTS".equals(currency)) {
            Instant expiresAt = Instant.parse(d.path("expiresAt").asString());
            LocalDate day = T0.atZone(WalTestbook.ROME).plusMonths(12).toLocalDate().withDayOfMonth(1).plusMonths(1).minusDays(1);
            assertThat(WalTestbook.isLastInstantOf(expiresAt, day)).as("%s: scadenza a fine mese +12 (%s)", id,
                    WalTestbook.describe(expiresAt)).isTrue();
        } else {
            assertThat(d.hasNonNull("expiresAt")).as("%s: STS senza scadenza", id).isFalse();
        }
    }

    @Order(1)
    @Test
    @DisplayName("[TB-WAL-GRT-059] stesso effectId due volte: un solo movimento")
    void grantIdempotent() {
        String m = freshMember("GRT-059");
        s.member(m, "SILVER", WalTestbook.threshold("SILVER"), "ACTIVE");
        String effectId = freshId("EFF");
        wallet.applyGrant(grant(m, effectId, "PTS", 130, true, 0, T0));
        wallet.applyGrant(grant(m, effectId, "PTS", 130, true, 0, T0));
        assertThat(s.ledger(m, "EARN")).hasSize(1);
        assertThat(s.allLots(m)).hasSize(1);
        assertThat(s.balance(m, "PTS").balanceActive()).isEqualTo(WalTestbook.floorTimes(130, "SILVER"));
        assertThat(s.memberFacts("wallet.points.earned", m)).hasSize(1);
    }

    @Order(1)
    @Test
    @DisplayName("[TB-WAL-GRT-060] membro senza wallet: wallet e livello BASE creati al volo")
    void grantCreatesWalletOnTheFly() {
        String m = freshMember("GRT-060");
        assertThat(wallets.findByMember(m)).isEmpty();
        wallet.applyGrant(grant(m, freshId("EFF"), "PTS", 100, true, 0, T0));
        assertThat(s.balance(m, "PTS").balanceActive()).isEqualTo(100);
        assertThat(s.tier(m).tierCode()).isEqualTo("BASE");
        assertThat(s.tier(m).periodSts()).isZero();
    }

    @Order(1)
    @Test
    @DisplayName("[TB-WAL-GRT-061] effetto senza effectId: ignorato (AMBIGUO)")
    void grantWithoutEffectIdIgnored() {
        // TESTBOOK: ambiguo, vedi TB-WAL-GRT-061 (il contratto rende effectId obbligatorio; nessuna fonte dice cosa fare)
        String m = freshMember("GRT-061");
        s.member(m, "BASE", 0, "ACTIVE");
        LhEvent<JsonNode> g = grant(m, freshId("EFF"), "PTS", 100, false, 0, T0);
        ((ObjectNode) g.data()).remove("effectId");
        wallet.applyGrant(g);
        assertThat(s.ledger(m)).isEmpty();
        assertThat(s.balance(m, "PTS").balanceActive()).isZero();
    }

    @Order(1)
    @Test
    @DisplayName("[TB-WAL-GRT-062] data di business nel passato: lotto e scadenza dal time dell'azione, non dall'orologio")
    void grantUsesBusinessTime() {
        String m = freshMember("GRT-062");
        s.member(m, "BASE", 0, "ACTIVE");
        Instant past = rome("2025-12-10T09:00:00");
        wallet.applyGrant(grant(m, freshId("EFF"), "PTS", 100, false, 0, past));
        Map<String, Object> lot = s.allLots(m).getFirst();
        assertThat(instant(lot.get("earned_at"))).isEqualTo(past);
        assertThat(instant(s.ledger(m, "EARN").getFirst().get("occurred_at"))).isEqualTo(past);
        JsonNode d = s.memberFacts("wallet.points.earned", m).getFirst().path("data");
        assertThat(WalTestbook.isLastInstantOf(Instant.parse(d.path("expiresAt").asString()), LocalDate.parse("2026-12-31"))).isTrue();
        JsonNode fact = s.memberFacts("wallet.points.earned", m).getFirst();
        assertThat(Instant.parse(fact.path("time").asString())).as("time del fatto = data di business").isEqualTo(past);
    }

    @Order(1)
    @Test
    @DisplayName("[TB-WAL-GRT-063] metadata del movimento con moltiplicatore di campagna: baseAmount, tierCode, tierMultiplier, campaignMultiplier")
    void grantMetadataWithCampaignMultiplier() {
        String m = freshMember("GRT-063");
        s.member(m, "SILVER", WalTestbook.threshold("SILVER"), "ACTIVE");
        LhEvent<JsonNode> g = grant(m, freshId("EFF"), "PTS", 130, true, 0, T0);
        ((ObjectNode) g.data()).put("baseAmount", 65);
        ((ObjectNode) g.data()).put("campaignMultiplier", 2.0);
        wallet.applyGrant(g);
        Map<String, Object> e = s.ledger(m, "EARN").getFirst();
        assertThat(num(e.get("amount"))).isEqualTo(WalTestbook.floorTimes(130, "SILVER"));
        JsonNode meta = MAPPER.readTree(e.get("metadata").toString());
        assertThat(meta.path("baseAmount").asLong()).isEqualTo(65);
        assertThat(meta.path("tierCode").asString()).isEqualTo("SILVER");
        assertThat(meta.path("tierMultiplier").decimalValue()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(WalTestbook.multiplier("SILVER"));
        assertThat(meta.path("campaignMultiplier").asDouble()).isEqualTo(2.0);
    }

    @Order(1)
    @Test
    @DisplayName("[TB-WAL-GRT-064] lotto PTS salvato: expiresAt = ultimo istante del mese di earned_at + 12 (Europe/Rome)")
    void persistedLotExpiryIsLastInstantOfMonth() {
        String m = freshMember("GRT-064");
        s.member(m, "BASE", 0, "ACTIVE");
        wallet.applyGrant(grant(m, freshId("EFF"), "PTS", 100, false, 0, T0));
        JsonNode lotsView = s.get("/v1/wallets/" + m + "/lots").body();
        Instant expiresAt = Instant.parse(lotsView.get(0).path("expiresAt").asString());
        assertThat(WalTestbook.isLastInstantOf(expiresAt, LocalDate.parse("2027-09-30")))
                .as("scadenza esposta dal servizio: %s", WalTestbook.describe(expiresAt)).isTrue();
    }

    @Order(1)
    @Test
    @DisplayName("[TB-WAL-GRT-065] effetto con subject che non è un membro: ignorato (AMBIGUO)")
    void grantWithoutMemberSubjectIgnored() {
        // TESTBOOK: ambiguo, vedi TB-WAL-GRT-065 (docs/05 §1: la chiave degli effetti è sempre il memberId)
        String effectId = freshId("EFF");
        LhEvent<JsonNode> g = grant("X", effectId, "PTS", 100, false, 0, T0);
        LhEvent<JsonNode> campaignSubject = WalTestbook.event(g.type(), "campaign:CMP-TESTBOOK", T0, g.data());
        wallet.applyGrant(campaignSubject);
        assertThat(jdbc.sql("SELECT count(*) FROM ledger_entry WHERE effect_id = ?").param(effectId)
                .query(Long.class).single()).isZero();
    }

    // =====================================================================================================
    // §7 Salita immediata di livello
    // =====================================================================================================

    @Order(2)
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/wal/tier-upgrade.csv", numLinesToSkip = 1)
    void tierUpgrade(String id, String description, String startTier, String startSts, String endSts, String currency,
                     String expectedTier, String upgradedFrom) {
        String m = freshMember(id);
        long start = sts(startSts);
        s.member(m, startTier, start, "ACTIVE");
        long amount = "PTS".equals(currency) ? 100_000 : sts(endSts) - start;

        wallet.applyGrant(grant(m, freshId("EFF"), currency, amount, false, 0, T0));

        long end = "STS".equals(currency) ? sts(endSts) : start;
        assertThat(s.tier(m).tierCode()).as("%s: livello", id).isEqualTo(expectedTier);
        assertThat(s.tier(m).periodSts()).as("%s: periodSts", id).isEqualTo(end);
        List<JsonNode> ups = s.memberFacts("tier.upgraded", m);
        JsonNode history = s.get("/v1/members/" + m + "/tier-history").body();
        long upgradeRows = 0;
        for (JsonNode h : history) {
            if ("UPGRADE".equals(h.path("kind").asString())) {
                upgradeRows++;
            }
        }
        if ("NONE".equals(upgradedFrom)) {
            assertThat(ups).as("%s: nessun tier.upgraded", id).isEmpty();
            assertThat(upgradeRows).isZero();
        } else {
            assertThat(ups).as("%s: un solo tier.upgraded (il livello più alto raggiunto)", id).hasSize(1);
            JsonNode d = ups.getFirst().path("data");
            assertThat(d.path("previousTier").asString()).isEqualTo(upgradedFrom);
            assertThat(d.path("newTier").asString()).isEqualTo(expectedTier);
            assertThat(d.path("periodSts").asLong()).isEqualTo(end);
            assertThat(upgradeRows).as("%s: storico UPGRADE", id).isEqualTo(1);
        }
    }

    @Order(3)
    @Test
    @DisplayName("[TB-WAL-TUP-016] STS in attesa che superano la soglia: nessuna salita finché sono PENDING (AMBIGUO)")
    void pendingStsDoesNotUpgrade() {
        // TESTBOOK: ambiguo, vedi TB-WAL-TUP-016 (docs/03 §4.3 non dice se gli STS in attesa contano già)
        String m = freshMember("TUP-016");
        s.member(m, "BASE", 0, "ACTIVE");
        wallet.applyGrant(grant(m, freshId("EFF"), "STS", WalTestbook.threshold("SILVER"), false, 3, T0));
        assertThat(s.tier(m).tierCode()).isEqualTo("BASE");
        assertThat(s.tier(m).periodSts()).isZero();
        assertThat(s.memberFacts("tier.upgraded", m)).isEmpty();
    }

    @Order(3)
    @Test
    @DisplayName("[TB-WAL-TUP-017] rilascio degli STS in attesa: contano nel periodo e fanno salire (AMBIGUO)")
    void releasedStsUpgrades() {
        // TESTBOOK: ambiguo, vedi TB-WAL-TUP-017
        String m = freshMember("TUP-017");
        s.member(m, "BASE", 0, "ACTIVE");
        wallet.applyGrant(grant(m, freshId("EFF"), "STS", WalTestbook.threshold("SILVER"), false, 3, T0));
        wallet.releasePending(T0.plus(3, ChronoUnit.DAYS));
        assertThat(s.tier(m).tierCode()).isEqualTo("SILVER");
        assertThat(s.tier(m).periodSts()).isEqualTo(WalTestbook.threshold("SILVER"));
        assertThat(s.memberFacts("tier.upgraded", m)).hasSize(1);
    }

    @Order(3)
    @Test
    @DisplayName("[TB-WAL-TUP-018] dopo la salita a GOLD un accredito PTS con moltiplicatore usa il moltiplicatore GOLD")
    void pointsAfterUpgradeUseNewMultiplier() {
        String m = freshMember("TUP-018");
        s.member(m, "BASE", 0, "ACTIVE");
        wallet.applyGrant(grant(m, freshId("EFF"), "STS", WalTestbook.threshold("GOLD"), false, 0, T0));
        wallet.applyGrant(grant(m, freshId("EFF"), "PTS", 130, true, 0, T0));
        assertThat(s.balance(m, "PTS").balanceActive()).isEqualTo(WalTestbook.floorTimes(130, "GOLD"));
    }

    @Order(3)
    @Test
    @DisplayName("[TB-WAL-TUP-019] storico livelli del membro: la salita compare con da/a (F-TIER-06)")
    void tierHistoryShowsUpgrade() {
        String m = freshMember("TUP-019");
        s.member(m, "BASE", 0, "ACTIVE");
        wallet.applyGrant(grant(m, freshId("EFF"), "STS", WalTestbook.threshold("SILVER"), false, 0, T0));
        JsonNode history = s.get("/v1/members/" + m + "/tier-history").body();
        assertThat(history).hasSize(1);
        assertThat(history.get(0).path("kind").asString()).isEqualTo("UPGRADE");
        assertThat(history.get(0).path("fromTier").asString()).isEqualTo("BASE");
        assertThat(history.get(0).path("toTier").asString()).isEqualTo("SILVER");
    }

    // =====================================================================================================
    // §4 Rilascio dei punti in attesa
    // =====================================================================================================

    @Order(4)
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/wal/release.csv", numLinesToSkip = 1)
    void releaseBoundary(String id, String description, long offsetSeconds, boolean released) {
        String m = freshMember(id);
        s.member(m, "BASE", 0, "ACTIVE");
        wallet.applyGrant(grant(m, freshId("EFF"), "PTS", 100, false, 2, T0));
        Instant availableAt = instant(s.allLots(m).getFirst().get("available_at"));

        WalItSupport.Resp r = s.http(HttpMethod.POST, "/v1/demo/jobs/release-pending?asOf=" + availableAt.plusSeconds(offsetSeconds), "ADMIN", null);

        assertThat(r.status()).isEqualTo(200);
        assertThat(s.allLots(m).getFirst().get("status")).as("%s", id).isEqualTo(released ? "ACTIVE" : "PENDING");
        assertThat(s.balance(m, "PTS").balanceActive()).isEqualTo(released ? 100 : 0);
        assertThat(s.balance(m, "PTS").balancePending()).isEqualTo(released ? 0 : 100);
        assertThat(s.ledger(m, "RELEASE")).hasSize(released ? 1 : 0);
    }

    @Order(4)
    @Test
    @DisplayName("[TB-WAL-REL-004] rilascio: movimento RELEASE informativo, saldo da in attesa ad attivo, fatto wallet.points.released")
    void releaseEffects() {
        String m = freshMember("REL-004");
        s.member(m, "BASE", 0, "ACTIVE");
        wallet.applyGrant(grant(m, freshId("EFF"), "PTS", 100, false, 2, T0));
        wallet.releasePending(T0.plus(2, ChronoUnit.DAYS));
        Map<String, Object> rel = s.ledger(m, "RELEASE").getFirst();
        assertThat(num(rel.get("amount"))).isEqualTo(100);
        assertThat(rel.get("direction")).isEqualTo("+");
        assertThat(num(rel.get("balance_after"))).isEqualTo(100);
        assertThat(s.balance(m, "PTS").balancePending()).isZero();
        assertThat(s.balance(m, "PTS").balanceActive()).isEqualTo(100);
        assertThat(s.balance(m, "PTS").lifetimeEarned()).as("il rilascio non è un nuovo guadagno").isEqualTo(100);
        JsonNode d = s.memberFacts("wallet.points.released", m).getFirst().path("data");
        assertThat(d.path("currency").asString()).isEqualTo("PTS");
        assertThat(d.path("amount").asLong()).isEqualTo(100);
        assertThat(d.path("balanceAfter").asLong()).isEqualTo(100);
    }

    @Order(4)
    @Test
    @DisplayName("[TB-WAL-REL-005] rilascio ripetuto con lo stesso asOf: nessun secondo RELEASE")
    void releaseIdempotent() {
        String m = freshMember("REL-005");
        s.member(m, "BASE", 0, "ACTIVE");
        wallet.applyGrant(grant(m, freshId("EFF"), "PTS", 100, false, 2, T0));
        Instant asOf = T0.plus(2, ChronoUnit.DAYS);
        wallet.releasePending(asOf);
        wallet.releasePending(asOf);
        assertThat(s.ledger(m, "RELEASE")).hasSize(1);
        assertThat(s.balance(m, "PTS").balanceActive()).isEqualTo(100);
    }

    // =====================================================================================================
    // §10 Vista del wallet: livello, prossimo livello, scadenze imminenti, avviso di mantenimento
    // =====================================================================================================

    @Order(5)
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/wal/wallet-view.csv", numLinesToSkip = 1)
    void walletViewNextTier(String id, String description, String tier, String periodSts, String nextTier, String missing) {
        String m = freshMember(id);
        s.member(m, tier, sts(periodSts), "ACTIVE");
        JsonNode t = s.get("/v1/wallets/" + m).body().path("tier");
        assertThat(t.path("code").asString()).isEqualTo(tier);
        assertThat(t.path("name").asString()).isEqualTo(WalTestbook.seedTier(tier).name());
        assertThat(t.path("periodSts").asLong()).isEqualTo(sts(periodSts));
        assertThat(t.path("multiplier").decimalValue()).usingComparator(BigDecimal::compareTo).isEqualTo(WalTestbook.multiplier(tier));
        if ("NONE".equals(nextTier)) {
            assertThat(t.hasNonNull("next")).as("%s: livello massimo, nessun prossimo", id).isFalse();
        } else {
            assertThat(t.path("next").path("code").asString()).isEqualTo(nextTier);
            assertThat(t.path("next").path("threshold").asLong()).isEqualTo(WalTestbook.threshold(nextTier));
            assertThat(t.path("next").path("missing").asLong()).as("%s: STS mancanti", id).isEqualTo(sts(missing));
        }
    }

    @Order(5)
    @Test
    @DisplayName("[TB-WAL-WVW-005] lotto in scadenza esattamente fra 30 giorni: conta in expiringSoon")
    void expiringSoonIncludesThirtyDays() {
        String m = freshMember("WVW-005");
        s.member(m, "BASE", 0, "ACTIVE");
        Instant at = NOW.plus(30, ChronoUnit.DAYS);
        s.lot(m, "PTS", 250, "ACTIVE", NOW.minus(300, ChronoUnit.DAYS), null, at);
        JsonNode soon = s.get("/v1/wallets/" + m).body().path("expiringSoon");
        assertThat(soon.path("amount").asLong()).isEqualTo(250);
        assertThat(soon.path("within30d").asBoolean()).isTrue();
        assertThat(Instant.parse(soon.path("nextExpiryAt").asString())).isEqualTo(at);
    }

    @Order(5)
    @Test
    @DisplayName("[TB-WAL-WVW-006] lotto in scadenza fra 30 giorni + 1 s: fuori da expiringSoon")
    void expiringSoonExcludesBeyondThirtyDays() {
        String m = freshMember("WVW-006");
        s.member(m, "BASE", 0, "ACTIVE");
        s.lot(m, "PTS", 250, "ACTIVE", NOW.minus(300, ChronoUnit.DAYS), null, NOW.plus(30, ChronoUnit.DAYS).plusSeconds(1));
        JsonNode soon = s.get("/v1/wallets/" + m).body().path("expiringSoon");
        assertThat(soon.path("amount").asLong()).isZero();
        assertThat(soon.path("within30d").asBoolean()).isFalse();
    }

    @Order(5)
    @Test
    @DisplayName("[TB-WAL-WVW-007] lotto già scaduto ma non ancora spazzato dal job: fuori da expiringSoon (AMBIGUO)")
    void expiringSoonExcludesAlreadyDue() {
        // TESTBOOK: ambiguo, vedi TB-WAL-WVW-007
        String m = freshMember("WVW-007");
        s.member(m, "BASE", 0, "ACTIVE");
        s.lot(m, "PTS", 250, "ACTIVE", NOW.minus(400, ChronoUnit.DAYS), null, NOW.minus(1, ChronoUnit.HOURS));
        JsonNode soon = s.get("/v1/wallets/" + m).body().path("expiringSoon");
        assertThat(soon.path("amount").asLong()).isZero();
    }

    @Order(5)
    @Test
    @DisplayName("[TB-WAL-WVW-008] più lotti in scadenza: somma dei PTS attivi entro 30 giorni e prima scadenza")
    void expiringSoonSumsAndEarliest() {
        String m = freshMember("WVW-008");
        s.member(m, "BASE", 0, "ACTIVE");
        Instant first = NOW.plus(5, ChronoUnit.DAYS);
        s.lot(m, "PTS", 200, "ACTIVE", NOW.minus(300, ChronoUnit.DAYS), null, first);
        s.lot(m, "PTS", 300, "ACTIVE", NOW.minus(300, ChronoUnit.DAYS), null, NOW.plus(20, ChronoUnit.DAYS));
        s.lot(m, "PTS", 900, "ACTIVE", NOW.minus(10, ChronoUnit.DAYS), null, NOW.plus(200, ChronoUnit.DAYS));
        s.lot(m, "STS", 400, "ACTIVE", NOW.minus(10, ChronoUnit.DAYS), null, null);
        JsonNode soon = s.get("/v1/wallets/" + m).body().path("expiringSoon");
        assertThat(soon.path("amount").asLong()).isEqualTo(500);
        assertThat(Instant.parse(soon.path("nextExpiryAt").asString())).isEqualTo(first);
    }

    @Order(5)
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/wal/keep-warning.csv", numLinesToSkip = 1)
    void keepWarning(String id, String description, String clockRome, String tier, String periodSts, String missing) {
        String m = freshMember(id);
        s.member(m, tier, sts(periodSts), "ACTIVE");
        CLOCK.set(rome(clockRome));
        JsonNode t = s.get("/v1/wallets/" + m).body().path("tier");
        if ("NONE".equals(missing)) {
            assertThat(t.hasNonNull("keepWarning")).as("%s: nessun avviso di mantenimento", id).isFalse();
        } else {
            assertThat(t.hasNonNull("keepWarning")).as("%s: keepWarning atteso (wallet-service §3, §5)", id).isTrue();
            assertThat(t.path("keepWarning").path("tier").asString()).isEqualTo(tier);
            assertThat(t.path("keepWarning").path("missing").asLong()).isEqualTo(sts(missing));
        }
    }

    @Order(5)
    @Test
    @DisplayName("[TB-WAL-WVW-016] wallet di un membro sconosciuto: 404")
    void unknownWallet() {
        WalItSupport.Resp r = s.get("/v1/wallets/" + freshMember("WVW-016"));
        assertThat(r.status()).isEqualTo(404);
    }

    @Order(5)
    @Test
    @DisplayName("[TB-WAL-WVW-017] wallet del portale: stessi saldi e livello della vista di gestione")
    void portalWalletMatchesManagement() {
        String m = freshMember("WVW-017");
        s.member(m, "SILVER", 1420, "ACTIVE");
        wallet.applyGrant(grant(m, freshId("EFF"), "PTS", 130, true, 0, T0));
        JsonNode mgmt = s.get("/v1/wallets/" + m).body();
        JsonNode portal = s.get("/v1/portal/wallets/" + m).body();
        assertThat(portal.path("balances")).isEqualTo(mgmt.path("balances"));
        assertThat(portal.path("tier").path("code")).isEqualTo(mgmt.path("tier").path("code"));
        assertThat(portal.path("tier").path("periodSts")).isEqualTo(mgmt.path("tier").path("periodSts"));
    }

    // =====================================================================================================
    // §11 API di lettura: libro mastro, lotti, portale, distribuzione
    // =====================================================================================================

    @Order(6)
    @Test
    @DisplayName("[TB-WAL-API-001] libro mastro ordinato per occurredAt decrescente")
    void ledgerOrderedByOccurredAtDesc() {
        String m = freshMember("API-001");
        s.member(m, "BASE", 0, "ACTIVE");
        wallet.applyGrant(grant(m, freshId("EFF"), "PTS", 10, false, 0, T0));
        wallet.applyGrant(grant(m, freshId("EFF"), "PTS", 20, false, 0, T0.minus(10, ChronoUnit.DAYS)));
        wallet.applyGrant(grant(m, freshId("EFF"), "PTS", 30, false, 0, T0.plus(1, ChronoUnit.DAYS)));
        JsonNode l = s.get("/v1/wallets/" + m + "/ledger").body();
        List<Long> amounts = new ArrayList<>();
        l.forEach(e -> amounts.add(e.path("amount").asLong()));
        assertThat(amounts).containsExactly(30L, 10L, 20L);
    }

    @Order(6)
    @Test
    @DisplayName("[TB-WAL-API-002] libro mastro filtrato per valuta")
    void ledgerFilterCurrency() {
        String m = freshMember("API-002");
        s.member(m, "BASE", 0, "ACTIVE");
        wallet.applyGrant(grant(m, freshId("EFF"), "PTS", 10, false, 0, T0));
        wallet.applyGrant(grant(m, freshId("EFF"), "STS", 20, false, 0, T0));
        JsonNode l = s.get("/v1/wallets/" + m + "/ledger?currency=STS").body();
        assertThat(l).hasSize(1);
        assertThat(l.get(0).path("currency").asString()).isEqualTo("STS");
    }

    @Order(6)
    @Test
    @DisplayName("[TB-WAL-API-003] libro mastro filtrato per tipo di movimento")
    void ledgerFilterType() {
        String m = freshMember("API-003");
        s.member(m, "BASE", 0, "ACTIVE");
        wallet.applyGrant(grant(m, freshId("EFF"), "PTS", 100, false, 0, T0));
        payments.spend(WalTestbook.redemptionRequested(m, freshId("RDM"), 40));
        JsonNode l = s.get("/v1/wallets/" + m + "/ledger?type=SPEND").body();
        List<String> types = new ArrayList<>();
        l.forEach(e -> types.add(e.path("type").asString()));
        assertThat(types).as("filtro type (wallet-service §3)").containsExactly("SPEND");
    }

    @Order(6)
    @Test
    @DisplayName("[TB-WAL-API-004] libro mastro filtrato per periodo from/to")
    void ledgerFilterPeriod() {
        String m = freshMember("API-004");
        s.member(m, "BASE", 0, "ACTIVE");
        wallet.applyGrant(grant(m, freshId("EFF"), "PTS", 10, false, 0, T0.minus(10, ChronoUnit.DAYS)));
        wallet.applyGrant(grant(m, freshId("EFF"), "PTS", 20, false, 0, T0));
        JsonNode l = s.get("/v1/wallets/" + m + "/ledger?from=" + T0.minus(1, ChronoUnit.DAYS) + "&to=" + T0.plus(1, ChronoUnit.DAYS)).body();
        List<Long> amounts = new ArrayList<>();
        l.forEach(e -> amounts.add(e.path("amount").asLong()));
        assertThat(amounts).as("filtri from/to (wallet-service §3)").containsExactly(20L);
    }

    @Order(6)
    @Test
    @DisplayName("[TB-WAL-API-005] movimento esposto con azione e attore (F-WAL-02)")
    void ledgerExposesActionAndActor() {
        String m = freshMember("API-005");
        s.member(m, "BASE", 0, "ACTIVE");
        String effectId = freshId("EFF");
        wallet.applyGrant(grant(m, effectId, "PTS", 10, false, 0, T0));
        JsonNode e = s.get("/v1/wallets/" + m + "/ledger").body().get(0);
        assertThat(e.path("campaignCode").asString()).isEqualTo("CMP-TESTBOOK");
        assertThat(e.path("actionId").asString(null)).as("azione del movimento").isEqualTo("ACT-" + effectId);
        assertThat(e.path("actor").asString(null)).as("attore del movimento").isEqualTo("SYSTEM:testbook");
    }

    @Order(6)
    @Test
    @DisplayName("[TB-WAL-API-006] lotti: solo non esauriti (ACTIVE e PENDING), per scadenza crescente, senza scadenza in fondo")
    void lotsView() {
        String m = freshMember("API-006");
        s.member(m, "BASE", 0, "ACTIVE");
        s.lot(m, "PTS", 100, "ACTIVE", NOW, null, rome("2027-03-31T23:59:59"));
        s.lot(m, "PTS", 200, "PENDING", NOW, NOW.plus(2, ChronoUnit.DAYS), rome("2026-12-31T23:59:59"));
        s.lot(m, "PTS", 300, "EXHAUSTED", NOW, null, rome("2026-10-31T23:59:59"));
        s.lot(m, "PTS", 400, "EXPIRED", NOW, null, rome("2026-09-01T23:59:59"));
        s.lot(m, "STS", 500, "ACTIVE", NOW, null, null);
        JsonNode l = s.get("/v1/wallets/" + m + "/lots").body();
        List<Long> amounts = new ArrayList<>();
        l.forEach(e -> amounts.add(e.path("amount").asLong()));
        assertThat(amounts).containsExactly(200L, 100L, 500L);
    }

    @Order(6)
    @Test
    @DisplayName("[TB-WAL-API-007] scala dei livelli del portale: code, name, threshold, multiplier, benefits, color dal seed")
    void portalTiers() {
        JsonNode l = s.get("/v1/portal/tiers").body();
        List<io.loyaltyhub.wallet.domain.Tier> scale = WalTestbook.seedScale();
        assertThat(l).hasSize(scale.size());
        for (int i = 0; i < scale.size(); i++) {
            JsonNode t = l.get(i);
            io.loyaltyhub.wallet.domain.Tier exp = scale.get(i);
            assertThat(t.path("code").asString()).isEqualTo(exp.code());
            assertThat(t.path("name").asString()).isEqualTo(exp.name());
            assertThat(t.path("color").asString()).isEqualTo(exp.color());
            assertThat(t.path("multiplier").decimalValue()).usingComparator(BigDecimal::compareTo).isEqualTo(exp.multiplier());
            assertThat(t.path("benefits")).hasSize(exp.benefits().size());
            assertThat(t.hasNonNull("threshold")).as("campo threshold (wallet-service §3, PT-08)").isTrue();
            assertThat(t.path("threshold").asLong()).isEqualTo(exp.thresholdSts());
        }
    }

    @Order(6)
    @Test
    @DisplayName("[TB-WAL-API-008] attività del portale: accredito in attesa marcato pending")
    void portalActivityPending() {
        String m = freshMember("API-008");
        s.member(m, "BASE", 0, "ACTIVE");
        wallet.applyGrant(grant(m, freshId("EFF"), "PTS", 100, false, 7, T0));
        JsonNode item = s.get("/v1/portal/wallets/" + m + "/activity").body().get(0);
        assertThat(item.path("pending").asBoolean()).as("pending (wallet-service §3, PT-07)").isTrue();
    }

    @Order(6)
    @Test
    @DisplayName("[TB-WAL-API-009] attività del portale: scomposizione «130 punti base × 1,25 livello SILVER = 162»")
    void portalActivityBreakdown() {
        String m = freshMember("API-009");
        s.member(m, "SILVER", WalTestbook.threshold("SILVER"), "ACTIVE");
        wallet.applyGrant(grant(m, freshId("EFF"), "PTS", 130, true, 0, T0));
        JsonNode item = s.get("/v1/portal/wallets/" + m + "/activity").body().get(0);
        String mult = WalTestbook.multiplier("SILVER").stripTrailingZeros().toPlainString().replace('.', ',');
        assertThat(item.path("breakdown").asString())
                .isEqualTo("130 punti base × " + mult + " livello SILVER = " + WalTestbook.floorTimes(130, "SILVER"));
        assertThat(item.path("amount").asLong()).isEqualTo(WalTestbook.floorTimes(130, "SILVER"));
    }

    @Order(6)
    @Test
    @DisplayName("[TB-WAL-API-010] attività del portale: accredito con data di scadenza del lotto")
    void portalActivityExpiresAt() {
        String m = freshMember("API-010");
        s.member(m, "BASE", 0, "ACTIVE");
        wallet.applyGrant(grant(m, freshId("EFF"), "PTS", 100, false, 0, T0));
        JsonNode item = s.get("/v1/portal/wallets/" + m + "/activity").body().get(0);
        assertThat(item.hasNonNull("expiresAt")).as("expiresAt (wallet-service §3, PT-07)").isTrue();
    }

    @Order(6)
    @Test
    @DisplayName("[TB-WAL-API-011] distribuzione per livello: un nuovo membro GOLD incrementa GOLD di 1")
    void tierDistribution() {
        long before = goldCount();
        s.member(freshMember("API-011"), "GOLD", WalTestbook.threshold("GOLD"), "ACTIVE");
        assertThat(goldCount()).isEqualTo(before + 1);
    }

    private long goldCount() {
        for (JsonNode t : s.get("/v1/tiers/distribution").body()) {
            if ("GOLD".equals(t.path("code").asString())) {
                return t.path("members").asLong();
            }
        }
        return -1;
    }

    // =====================================================================================================
    // §12 Ciclo di vita del membro (fatti member.*)
    // =====================================================================================================

    @Order(7)
    @Test
    @DisplayName("[TB-WAL-MBR-001] member.registered: due wallet PTS e STS a zero e livello BASE")
    void memberRegistered() {
        String m = freshMember("MBR-001");
        router.route(WalTestbook.event("io.loyaltyhub.fact.member.registered", "member:" + m, NOW, MAPPER.createObjectNode()));
        assertThat(wallets.findByMember(m)).extracting(io.loyaltyhub.wallet.domain.WalletBalance::currency)
                .containsExactly("PTS", "STS");
        assertThat(wallets.findByMember(m)).allMatch(w -> w.balanceActive() == 0 && w.balancePending() == 0);
        assertThat(s.tier(m).tierCode()).isEqualTo("BASE");
        assertThat(s.tier(m).periodSts()).isZero();
    }

    @Order(7)
    @Test
    @DisplayName("[TB-WAL-MBR-002] member.registered ripetuto dopo un accredito: saldi e livello invariati")
    void memberRegisteredIdempotent() {
        String m = freshMember("MBR-002");
        router.route(WalTestbook.event("io.loyaltyhub.fact.member.registered", "member:" + m, NOW, MAPPER.createObjectNode()));
        wallet.applyGrant(grant(m, freshId("EFF"), "STS", WalTestbook.threshold("SILVER"), false, 0, T0));
        router.route(WalTestbook.event("io.loyaltyhub.fact.member.registered", "member:" + m, NOW, MAPPER.createObjectNode()));
        assertThat(wallets.findByMember(m)).hasSize(2);
        assertThat(s.balance(m, "STS").balanceActive()).isEqualTo(WalTestbook.threshold("SILVER"));
        assertThat(s.tier(m).tierCode()).isEqualTo("SILVER");
    }

    @Order(7)
    @Test
    @DisplayName("[TB-WAL-MBR-003] member.status.changed a BLOCKED: la spesa successiva è rifiutata MEMBER_NOT_ACTIVE")
    void memberStatusChanged() {
        String m = freshMember("MBR-003");
        s.member(m, "BASE", 0, "ACTIVE");
        wallet.applyGrant(grant(m, freshId("EFF"), "PTS", 500, false, 0, T0));
        ObjectNode d = MAPPER.createObjectNode();
        d.put("previousStatus", "ACTIVE");
        d.put("newStatus", "BLOCKED");
        router.route(WalTestbook.event("io.loyaltyhub.fact.member.status.changed", "member:" + m, NOW, d));
        assertThat(s.tier(m).memberStatus()).isEqualTo("BLOCKED");
        String rdm = freshId("RDM");
        payments.spend(WalTestbook.redemptionRequested(m, rdm, 100));
        JsonNode rej = s.memberFacts("wallet.spend.rejected", m).getFirst().path("data");
        assertThat(rej.path("reason").asString()).isEqualTo("MEMBER_NOT_ACTIVE");
        assertThat(s.balance(m, "PTS").balanceActive()).isEqualTo(500);
    }

    // =====================================================================================================
    // §13 Passività (F-WAL-09)
    // =====================================================================================================

    @Order(8)
    @Test
    @DisplayName("[TB-WAL-LIA-001] accredito attivo: outstanding +importo e Σ byExpiryMonth +importo, pending invariato")
    void liabilityActiveGrant() {
        JsonNode before = s.get("/v1/liability?currency=PTS").body();
        String m = freshMember("LIA-001");
        s.member(m, "BASE", 0, "ACTIVE");
        wallet.applyGrant(grant(m, freshId("EFF"), "PTS", 100, false, 0, T0));
        JsonNode after = s.get("/v1/liability?currency=PTS").body();
        assertThat(after.path("outstanding").asLong() - before.path("outstanding").asLong()).isEqualTo(100);
        assertThat(after.path("pending").asLong() - before.path("pending").asLong()).isZero();
        assertThat(sumMonths(after) - sumMonths(before)).isEqualTo(100);
    }

    @Order(8)
    @Test
    @DisplayName("[TB-WAL-LIA-002] accredito in attesa: pending +importo, outstanding invariato")
    void liabilityPendingGrant() {
        JsonNode before = s.get("/v1/liability?currency=PTS").body();
        String m = freshMember("LIA-002");
        s.member(m, "BASE", 0, "ACTIVE");
        wallet.applyGrant(grant(m, freshId("EFF"), "PTS", 70, false, 5, T0));
        JsonNode after = s.get("/v1/liability?currency=PTS").body();
        assertThat(after.path("pending").asLong() - before.path("pending").asLong()).isEqualTo(70);
        assertThat(after.path("outstanding").asLong() - before.path("outstanding").asLong()).isZero();
    }

    @Order(8)
    @Test
    @DisplayName("[TB-WAL-LIA-003] PTS: somma per mese di scadenza = outstanding (saldo = Σ lotti attivi)")
    void liabilityMonthsSumToOutstanding() {
        JsonNode l = s.get("/v1/liability?currency=PTS").body();
        assertThat(sumMonths(l)).isEqualTo(l.path("outstanding").asLong());
    }

    @Order(8)
    @Test
    @DisplayName("[TB-WAL-LIA-004] STS: lotti senza scadenza (mese nullo) e somma = outstanding")
    void liabilitySts() {
        String m = freshMember("LIA-004");
        s.member(m, "BASE", 0, "ACTIVE");
        wallet.applyGrant(grant(m, freshId("EFF"), "STS", 50, false, 0, T0));
        JsonNode l = s.get("/v1/liability?currency=sts").body();
        assertThat(l.path("currency").asString()).isEqualTo("STS");
        assertThat(sumMonths(l)).isEqualTo(l.path("outstanding").asLong());
        for (JsonNode e : l.path("byExpiryMonth")) {
            assertThat(e.hasNonNull("month")).isFalse();
        }
    }

    @Order(8)
    @Test
    @DisplayName("[TB-WAL-LIA-005] valuta sconosciuta: 404")
    void liabilityUnknownCurrency() {
        assertThat(s.get("/v1/liability?currency=XYZ").status()).isEqualTo(404);
    }

    @Order(8)
    @Test
    @DisplayName("[TB-WAL-LIA-006] lotto che scade l'ultimo istante di settembre 2027: conteggiato nel mese 2027-09")
    void liabilityMonthOfLastInstant() {
        JsonNode before = s.get("/v1/liability?currency=PTS").body();
        String m = freshMember("LIA-006");
        s.member(m, "BASE", 0, "ACTIVE");
        wallet.applyGrant(grant(m, freshId("EFF"), "PTS", 77, false, 0, T0));
        JsonNode after = s.get("/v1/liability?currency=PTS").body();
        assertThat(month(after, "2027-09") - month(before, "2027-09")).as("delta del mese 2027-09").isEqualTo(77);
    }

    @Order(8)
    @Test
    @DisplayName("[TB-WAL-LIA-007] job di scadenza: outstanding diminuisce esattamente dei punti scaduti")
    void liabilityAfterExpiry() {
        String m = freshMember("LIA-007");
        s.member(m, "BASE", 0, "ACTIVE");
        s.lot(m, "PTS", 90, "ACTIVE", NOW.minus(360, ChronoUnit.DAYS), null, NOW.plus(1, ChronoUnit.DAYS));
        JsonNode before = s.get("/v1/liability?currency=PTS").body();
        WalletService.JobOutcome out = wallet.expirePoints(NOW.plus(1, ChronoUnit.DAYS));
        JsonNode after = s.get("/v1/liability?currency=PTS").body();
        assertThat(out.amount()).isGreaterThanOrEqualTo(90);
        assertThat(before.path("outstanding").asLong() - after.path("outstanding").asLong()).isEqualTo(out.amount());
        assertThat(sumMonths(after)).isEqualTo(after.path("outstanding").asLong());
    }

    private static long sumMonths(JsonNode liability) {
        long sum = 0;
        for (JsonNode e : liability.path("byExpiryMonth")) {
            sum += e.path("amount").asLong();
        }
        return sum;
    }

    private static long month(JsonNode liability, String month) {
        for (JsonNode e : liability.path("byExpiryMonth")) {
            if (month.equals(e.path("month").asString(null))) {
                return e.path("amount").asLong();
            }
        }
        return 0;
    }

    // =====================================================================================================
    // §6 Job demo: ruoli, asOf, audit
    // =====================================================================================================

    @Order(9)
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/wal/job-roles.csv", numLinesToSkip = 1)
    void jobRoles(String id, String description, String job, String actor, int status) {
        WalItSupport.Resp r = s.http(HttpMethod.POST, "/v1/demo/jobs/" + job + "?asOf=2000-01-01T00:00:00Z", actor, null);
        assertThat(r.status()).as("%s", id).isEqualTo(status);
        if (status == 403) {
            assertThat(r.code()).isEqualTo("FORBIDDEN_ROLE");
        }
    }

    @Order(9)
    @Test
    @DisplayName("[TB-WAL-JOB-012] job senza asOf: usa l'istante corrente")
    void jobWithoutAsOfUsesNow() {
        String m = freshMember("JOB-012");
        s.member(m, "BASE", 0, "ACTIVE");
        s.lot(m, "PTS", 60, "ACTIVE", NOW.minus(365, ChronoUnit.DAYS), null, NOW.minusSeconds(1));
        String keep = s.lot(m, "PTS", 40, "ACTIVE", NOW.minus(10, ChronoUnit.DAYS), null, NOW.plusSeconds(1));
        assertThat(s.http(HttpMethod.POST, "/v1/demo/jobs/expire-points", "ADMIN", null).status()).isEqualTo(200);
        assertThat(s.balance(m, "PTS").balanceActive()).isEqualTo(40);
        assertThat(s.lotRow(keep).get("status")).isEqualTo("ACTIVE");
    }

    @Order(9)
    @Test
    @DisplayName("[TB-WAL-JOB-013] ogni esecuzione di un job produce una voce di audit JOB")
    void jobIsAudited() {
        Instant since = Instant.now().minusSeconds(1);
        assertThat(s.http(HttpMethod.POST, "/v1/demo/jobs/expire-points?asOf=2000-01-01T00:00:00Z", "ADMIN", null).status()).isEqualTo(200);
        assertThat(s.countAuditSince("JOB", since)).as("audit dei job (wallet-service §4)").isGreaterThanOrEqualTo(1);
    }

    @Order(9)
    @Test
    @DisplayName("[TB-WAL-JOB-014] asOf come data pura: fine di quel giorno a Roma, scadono i lotti di quel giorno (AMBIGUO)")
    void jobAsOfDateOnly() {
        // TESTBOOK: ambiguo, vedi TB-WAL-JOB-014
        String m = freshMember("JOB-014");
        s.member(m, "BASE", 0, "ACTIVE");
        String lot = s.lot(m, "PTS", 60, "ACTIVE", NOW.minus(300, ChronoUnit.DAYS), null, rome("2026-10-31T23:59:59"));
        assertThat(s.http(HttpMethod.POST, "/v1/demo/jobs/expire-points?asOf=2026-10-31", "ADMIN", null).status()).isEqualTo(200);
        assertThat(s.lotRow(lot).get("status")).isEqualTo("EXPIRED");
    }

    // =====================================================================================================
    // §5 Job di scadenza
    // =====================================================================================================

    @Order(10)
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/wal/expiry-job.csv", numLinesToSkip = 1)
    void expiryBoundary(String id, String description, String earnedRome, String asOfRome, boolean expired) {
        String m = freshMember(id);
        s.member(m, "BASE", 0, "ACTIVE");
        wallet.applyGrant(grant(m, freshId("EFF"), "PTS", 100, false, 0, rome(earnedRome)));

        WalItSupport.Resp r = s.http(HttpMethod.POST, "/v1/demo/jobs/expire-points?asOf=" + rome(asOfRome), "ADMIN", null);

        assertThat(r.status()).isEqualTo(200);
        Map<String, Object> lot = s.allLots(m).getFirst();
        assertThat(lot.get("status")).as("%s: stato del lotto dopo il job", id).isEqualTo(expired ? "EXPIRED" : "ACTIVE");
        assertThat(s.balance(m, "PTS").balanceActive()).isEqualTo(expired ? 0 : 100);
        List<Map<String, Object>> exp = s.ledger(m, "EXPIRE");
        assertThat(exp).hasSize(expired ? 1 : 0);
        if (expired) {
            assertThat(num(exp.getFirst().get("amount"))).isEqualTo(100);
            assertThat(exp.getFirst().get("direction")).isEqualTo("-");
            assertThat(num(exp.getFirst().get("balance_after"))).isZero();
            assertThat(s.balance(m, "PTS").lifetimeExpired()).isEqualTo(100);
            JsonNode d = s.memberFacts("wallet.points.expired", m).getFirst().path("data");
            assertThat(d.path("currency").asString()).isEqualTo("PTS");
            assertThat(d.path("amount").asLong()).isEqualTo(100);
            assertThat(d.path("balanceAfter").asLong()).isZero();
        } else {
            assertThat(s.memberFacts("wallet.points.expired", m)).isEmpty();
        }
    }

    @Order(10)
    @Test
    @DisplayName("[TB-WAL-EXP-011] due lotti dello stesso membro scaduti nello stesso job: un solo movimento EXPIRE col totale")
    void expiryOneMovementPerMember() {
        String m = freshMember("EXP-011");
        s.member(m, "BASE", 0, "ACTIVE");
        s.lot(m, "PTS", 100, "ACTIVE", NOW.minus(360, ChronoUnit.DAYS), null, rome("2026-10-10T23:59:59"));
        s.lot(m, "PTS", 200, "ACTIVE", NOW.minus(350, ChronoUnit.DAYS), null, rome("2026-10-20T23:59:59"));
        wallet.expirePoints(rome("2026-10-21T00:00:00"));
        List<Map<String, Object>> exp = s.ledger(m, "EXPIRE");
        assertThat(exp).as("un movimento EXPIRE per membro/valuta (docs/03 §4.2)").hasSize(1);
        assertThat(num(exp.getFirst().get("amount"))).isEqualTo(300);
        assertThat(s.balance(m, "PTS").balanceActive()).isZero();
    }

    @Order(10)
    @Test
    @DisplayName("[TB-WAL-EXP-012] lotto consumato in parte: scade solo il residuo")
    void expiryOfPartiallySpentLot() {
        String m = freshMember("EXP-012");
        s.member(m, "BASE", 0, "ACTIVE");
        s.lot(m, "PTS", 500, "ACTIVE", NOW.minus(360, ChronoUnit.DAYS), null, rome("2026-10-10T23:59:59"));
        payments.spend(WalTestbook.redemptionRequested(m, freshId("RDM"), 200));
        wallet.expirePoints(rome("2026-10-11T00:00:00"));
        assertThat(num(s.ledger(m, "EXPIRE").getFirst().get("amount"))).isEqualTo(300);
        assertThat(s.balance(m, "PTS").balanceActive()).isZero();
        assertThat(s.balance(m, "PTS").lifetimeExpired()).isEqualTo(300);
    }

    @Order(10)
    @Test
    @DisplayName("[TB-WAL-EXP-013] lotto esaurito dalla spesa: nessuna scadenza")
    void expiryIgnoresExhausted() {
        String m = freshMember("EXP-013");
        s.member(m, "BASE", 0, "ACTIVE");
        String lot = s.lot(m, "PTS", 500, "ACTIVE", NOW.minus(360, ChronoUnit.DAYS), null, rome("2026-10-10T23:59:59"));
        payments.spend(WalTestbook.redemptionRequested(m, freshId("RDM"), 500));
        wallet.expirePoints(rome("2026-10-11T00:00:00"));
        assertThat(s.lotRow(lot).get("status")).isEqualTo("EXHAUSTED");
        assertThat(s.ledger(m, "EXPIRE")).isEmpty();
    }

    @Order(10)
    @Test
    @DisplayName("[TB-WAL-EXP-014] lotto PENDING con scadenza passata: non scade (solo i lotti ACTIVE)")
    void expiryIgnoresPending() {
        String m = freshMember("EXP-014");
        s.member(m, "BASE", 0, "ACTIVE");
        String lot = s.lot(m, "PTS", 80, "PENDING", NOW.minus(10, ChronoUnit.DAYS), rome("2027-01-01T00:00:00"), rome("2026-10-10T23:59:59"));
        wallet.expirePoints(rome("2026-10-11T00:00:00"));
        assertThat(s.lotRow(lot).get("status")).isEqualTo("PENDING");
        assertThat(s.balance(m, "PTS").balancePending()).isEqualTo(80);
        assertThat(s.ledger(m, "EXPIRE")).isEmpty();
    }

    @Order(10)
    @Test
    @DisplayName("[TB-WAL-EXP-015] job ripetuto con lo stesso asOf: nessun secondo EXPIRE")
    void expiryIdempotent() {
        String m = freshMember("EXP-015");
        s.member(m, "BASE", 0, "ACTIVE");
        s.lot(m, "PTS", 100, "ACTIVE", NOW.minus(360, ChronoUnit.DAYS), null, rome("2026-10-10T23:59:59"));
        Instant asOf = rome("2026-10-11T00:00:00");
        wallet.expirePoints(asOf);
        wallet.expirePoints(asOf);
        assertThat(s.ledger(m, "EXPIRE")).hasSize(1);
        assertThat(s.balance(m, "PTS").lifetimeExpired()).isEqualTo(100);
    }

    @Order(11)
    @Test
    @DisplayName("[TB-WAL-EXP-016] STS senza scadenza: non scadono mai")
    void stsNeverExpires() {
        String m = freshMember("EXP-016");
        s.member(m, "BASE", 0, "ACTIVE");
        wallet.applyGrant(grant(m, freshId("EFF"), "STS", 100, false, 0, T0));
        wallet.expirePoints(rome("2035-01-01T00:00:00"));
        assertThat(s.allLots(m).getFirst().get("status")).isEqualTo("ACTIVE");
        assertThat(s.balance(m, "STS").balanceActive()).isEqualTo(100);
    }

    // =====================================================================================================
    // §5 Preavvisi di scadenza
    // =====================================================================================================

    static final Instant WARN_AS_OF = rome("2026-10-01T09:00:00");

    @Order(12)
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/wal/warnings.csv", numLinesToSkip = 1)
    void warningsWindow(String id, String description, String offsetSeconds, String status, String currency, boolean warned) {
        String m = freshMember(id);
        s.member(m, "BASE", 0, "ACTIVE");
        Instant expiresAt = "NONE".equals(offsetSeconds) ? null : WARN_AS_OF.plusSeconds(Long.parseLong(offsetSeconds));
        s.lot(m, currency, 120, status, NOW.minus(300, ChronoUnit.DAYS),
                "PENDING".equals(status) ? WARN_AS_OF.plus(1, ChronoUnit.DAYS) : null, expiresAt);

        WalItSupport.Resp r = s.http(HttpMethod.POST, "/v1/demo/jobs/expiry-warnings?asOf=" + WARN_AS_OF, "ADMIN", null);

        assertThat(r.status()).isEqualTo(200);
        assertThat(s.memberFacts("wallet.points.expiring", m)).as("%s: preavviso", id).hasSize(warned ? 1 : 0);
    }

    @Order(12)
    @Test
    @DisplayName("[TB-WAL-WRN-008] preavviso una sola volta per lotto")
    void warningOncePerLot() {
        String m = freshMember("WRN-008");
        s.member(m, "BASE", 0, "ACTIVE");
        s.lot(m, "PTS", 120, "ACTIVE", NOW.minus(300, ChronoUnit.DAYS), null, WARN_AS_OF.plus(10, ChronoUnit.DAYS));
        wallet.expiryWarnings(WARN_AS_OF);
        wallet.expiryWarnings(WARN_AS_OF.plus(1, ChronoUnit.DAYS));
        assertThat(s.memberFacts("wallet.points.expiring", m)).hasSize(1);
    }

    @Order(12)
    @Test
    @DisplayName("[TB-WAL-WRN-009] fatto wallet.points.expiring: valuta, residuo del lotto, scadenza")
    void warningFactContent() {
        String m = freshMember("WRN-009");
        s.member(m, "BASE", 0, "ACTIVE");
        Instant exp = WARN_AS_OF.plus(10, ChronoUnit.DAYS);
        s.lot(m, "PTS", 500, "ACTIVE", NOW.minus(300, ChronoUnit.DAYS), null, exp);
        payments.spend(WalTestbook.redemptionRequested(m, freshId("RDM"), 150));
        wallet.expiryWarnings(WARN_AS_OF);
        JsonNode d = s.memberFacts("wallet.points.expiring", m).getFirst().path("data");
        assertThat(d.path("currency").asString()).isEqualTo("PTS");
        assertThat(d.path("amount").asLong()).isEqualTo(350);
        assertThat(Instant.parse(d.path("expiresAt").asString())).isEqualTo(exp);
    }
}
