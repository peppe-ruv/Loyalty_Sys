package io.loyaltyhub.wallet.testbook;

import io.loyaltyhub.common.event.LhEvent;
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

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.loyaltyhub.wallet.testbook.WalItSupport.instant;
import static io.loyaltyhub.wallet.testbook.WalItSupport.num;
import static io.loyaltyhub.wallet.testbook.WalTestbook.MAPPER;
import static io.loyaltyhub.wallet.testbook.WalTestbook.freshId;
import static io.loyaltyhub.wallet.testbook.WalTestbook.freshMember;
import static io.loyaltyhub.wallet.testbook.WalTestbook.rome;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-WAL (docs/testbook/TB-WAL-wallet.md) — spesa FIFO della saga di richiesta premio, rimborso e rettifiche
 * manuali. Servizio reale su Postgres embedded, profilo {@code demo}, orologio fisso, membri e lotti preparati per riga.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TestbookWalSpendIT {

    static final Instant NOW = Instant.parse("2026-09-24T08:00:00Z");
    static final WalTestbook.MutableClock CLOCK = new WalTestbook.MutableClock(NOW);
    private static final EmbeddedPostgres PG = TestbookWalAccrualIT.startPg();

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

    @Value("${local.server.port}")
    int port;
    @Autowired
    WalletService wallet;
    @Autowired
    RedemptionPayments payments;
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

    /** Scadenza dei lotti preparati: 23:59:59 del giorno indicato, a Roma. */
    static Instant endOfDay(String day) {
        return rome(day + "T23:59:59");
    }

    /** Lotti descritti come {@code NOME:importo:stato:guadagnato:scadenza[:valuta]}, separati da {@code ;}. */
    private Map<String, String> prepareLots(String memberId, String spec) {
        Map<String, String> ids = new LinkedHashMap<>();
        for (String item : spec.split(";")) {
            String[] p = item.split(":");
            String currency = p.length > 5 ? p[5] : "PTS";
            Instant earned = rome(p[3] + "T10:00:00");
            Instant expires = "NONE".equals(p[4]) ? null : endOfDay(p[4]);
            Instant available = "PENDING".equals(p[2]) ? NOW.plus(3, ChronoUnit.DAYS) : null;
            ids.put(p[0], s.lot(memberId, currency, Long.parseLong(p[1]), p[2], earned, available, expires));
        }
        return ids;
    }

    // =====================================================================================================
    // §8 Spesa della saga di richiesta premio
    // =====================================================================================================

    @Order(1)
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/wal/spend.csv", numLinesToSkip = 1)
    void spend(String id, String description, String status, String lotSpec, long cost, String expected) {
        String m = freshMember(id);
        s.member(m, "BASE", 0, status);
        Map<String, String> ids = prepareLots(m, lotSpec);
        Map<String, Long> before = new LinkedHashMap<>();
        ids.forEach((name, lotId) -> before.put(name, num(s.lotRow(lotId).get("remaining"))));
        long activeBefore = s.balance(m, "PTS").balanceActive();
        String rdm = freshId("RDM");

        // Riga 018 — TESTBOOK: ambiguo, vedi TB-WAL-SPD-018
        payments.spend(WalTestbook.redemptionRequested(m, rdm, cost));

        String[] e = expected.split(":");
        if ("REJECTED".equals(e[0])) {
            List<JsonNode> rej = s.memberFacts("wallet.spend.rejected", m);
            assertThat(rej).as("%s: un fatto wallet.spend.rejected", id).hasSize(1);
            JsonNode d = rej.get(0).path("data");
            assertThat(d.path("redemptionId").asString()).isEqualTo(rdm);
            assertThat(d.path("reason").asString()).as("%s: motivo", id).isEqualTo(e[1]);
            assertThat(d.path("requested").asLong()).isEqualTo(cost);
            assertThat(d.path("available").asLong()).as("%s: disponibile", id).isEqualTo(Long.parseLong(e[2]));
            assertThat(s.ledger(m, "SPEND")).as("%s: nessun movimento", id).isEmpty();
            assertThat(s.memberFacts("wallet.points.spent", m)).isEmpty();
            assertThat(s.balance(m, "PTS").balanceActive()).isEqualTo(activeBefore);
            ids.forEach((name, lotId) -> assertThat(num(s.lotRow(lotId).get("remaining"))).isEqualTo(before.get(name)));
            return;
        }
        Map<String, Long> taken = new LinkedHashMap<>();
        for (String kv : e[1].split(" ")) {
            String[] x = kv.split("=");
            taken.put(x[0], Long.parseLong(x[1]));
        }
        List<Map<String, Object>> spends = s.ledger(m, "SPEND");
        assertThat(spends).as("%s: un movimento SPEND", id).hasSize(1);
        Map<String, Object> sp = spends.getFirst();
        assertThat(num(sp.get("amount"))).isEqualTo(cost);
        assertThat(sp.get("direction")).isEqualTo("-");
        assertThat(sp.get("currency")).isEqualTo("PTS");
        assertThat(sp.get("redemption_id")).isEqualTo(rdm);
        assertThat(num(sp.get("balance_after"))).isEqualTo(activeBefore - cost);
        for (Map.Entry<String, String> lot : ids.entrySet()) {
            long t = taken.getOrDefault(lot.getKey(), 0L);
            Map<String, Object> row = s.lotRow(lot.getValue());
            assertThat(num(row.get("remaining"))).as("%s: residuo del lotto %s", id, lot.getKey()).isEqualTo(before.get(lot.getKey()) - t);
            if (t > 0 && before.get(lot.getKey()) == t) {
                assertThat(row.get("status")).as("%s: lotto %s esaurito", id, lot.getKey()).isEqualTo("EXHAUSTED");
            }
        }
        Map<String, Long> consumed = new LinkedHashMap<>();
        for (Map<String, Object> c : s.consumptions((String) sp.get("id"))) {
            consumed.put((String) c.get("lot_id"), num(c.get("amount")));
        }
        Map<String, Long> expectedConsumption = new LinkedHashMap<>();
        taken.forEach((name, amount) -> expectedConsumption.put(ids.get(name), amount));
        assertThat(consumed).as("%s: lot_consumption", id).isEqualTo(expectedConsumption);
        assertThat(s.balance(m, "PTS").balanceActive()).isEqualTo(activeBefore - cost);
        assertThat(s.activeLotsSum(m, "PTS")).as("%s: saldo = Σ lotti attivi", id).isEqualTo(activeBefore - cost);
        JsonNode d = s.memberFacts("wallet.points.spent", m).getFirst().path("data");
        assertThat(d.path("ledgerEntryId").asString()).isEqualTo(sp.get("id"));
        assertThat(d.path("currency").asString()).isEqualTo("PTS");
        assertThat(d.path("amount").asLong()).isEqualTo(cost);
        assertThat(d.path("balanceAfter").asLong()).isEqualTo(activeBefore - cost);
        assertThat(d.path("redemptionId").asString()).isEqualTo(rdm);
    }

    @Order(1)
    @Test
    @DisplayName("[TB-WAL-SPD-020] stessa richiesta (redemptionId) elaborata due volte: una sola spesa")
    void spendIdempotent() {
        String m = freshMember("SPD-020");
        s.member(m, "BASE", 0, "ACTIVE");
        prepareLots(m, "A:500:ACTIVE:2025-12-10:2026-12-31");
        String rdm = freshId("RDM");
        payments.spend(WalTestbook.redemptionRequested(m, rdm, 200));
        payments.spend(WalTestbook.redemptionRequested(m, rdm, 200));
        assertThat(s.ledger(m, "SPEND")).hasSize(1);
        assertThat(s.memberFacts("wallet.points.spent", m)).hasSize(1);
        assertThat(s.balance(m, "PTS").balanceActive()).isEqualTo(300);
    }

    @Order(1)
    @Test
    @DisplayName("[TB-WAL-SPD-021] richiesta senza redemptionId: ignorata (AMBIGUO)")
    void spendWithoutRedemptionIdIgnored() {
        // TESTBOOK: ambiguo, vedi TB-WAL-SPD-021
        String m = freshMember("SPD-021");
        s.member(m, "BASE", 0, "ACTIVE");
        prepareLots(m, "A:500:ACTIVE:2025-12-10:2026-12-31");
        LhEvent<JsonNode> ev = WalTestbook.redemptionRequested(m, freshId("RDM"), 200);
        ((ObjectNode) ev.data()).remove("redemptionId");
        payments.spend(ev);
        assertThat(s.ledger(m, "SPEND")).isEmpty();
        assertThat(s.memberFacts("wallet.spend.rejected", m)).isEmpty();
        assertThat(s.balance(m, "PTS").balanceActive()).isEqualTo(500);
    }

    @Order(1)
    @Test
    @DisplayName("[TB-WAL-SPD-022] richiesta con pointsCost 0: ignorata (AMBIGUO)")
    void spendZeroCostIgnored() {
        // TESTBOOK: ambiguo, vedi TB-WAL-SPD-022
        String m = freshMember("SPD-022");
        s.member(m, "BASE", 0, "ACTIVE");
        prepareLots(m, "A:500:ACTIVE:2025-12-10:2026-12-31");
        payments.spend(WalTestbook.redemptionRequested(m, freshId("RDM"), 0));
        assertThat(s.ledger(m, "SPEND")).isEmpty();
        assertThat(s.memberFacts("wallet.spend.rejected", m)).isEmpty();
        assertThat(s.memberFacts("wallet.points.spent", m)).isEmpty();
    }

    @Order(1)
    @Test
    @DisplayName("[TB-WAL-SPD-023] membro senza wallet: rifiuto INSUFFICIENT_BALANCE con disponibile 0 (AMBIGUO)")
    void spendWithoutWallet() {
        // TESTBOOK: ambiguo, vedi TB-WAL-SPD-023
        String m = freshMember("SPD-023");
        payments.spend(WalTestbook.redemptionRequested(m, freshId("RDM"), 100));
        JsonNode d = s.memberFacts("wallet.spend.rejected", m).getFirst().path("data");
        assertThat(d.path("reason").asString()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(d.path("available").asLong()).isZero();
    }

    // =====================================================================================================
    // §8 Rimborso (reward.redemption.cancelled con refund=true)
    // =====================================================================================================

    /** Scenario di accettazione: [500 ott, 800 dic, 900 mar] e spesa di 1500 (A=500, B=800, C=200). */
    private record Refunded(String member, String redemption, Map<String, String> lots) {
    }

    private Refunded spendAcceptance(String row, boolean refund) {
        String m = freshMember(row);
        s.member(m, "BASE", 0, "ACTIVE");
        Map<String, String> ids = prepareLots(m,
                "A:500:ACTIVE:2025-10-10:2026-10-31;B:800:ACTIVE:2025-12-10:2026-12-31;C:900:ACTIVE:2026-03-10:2027-03-31");
        String rdm = freshId("RDM");
        payments.spend(WalTestbook.redemptionRequested(m, rdm, 1500));
        if (refund) {
            payments.refund(WalTestbook.redemptionCancelled(m, rdm, 1500, true));
        }
        return new Refunded(m, rdm, ids);
    }

    @Order(2)
    @Test
    @DisplayName("[TB-WAL-REF-001] rimborso totale: saldo attivo tornato a quello prima della spesa")
    void refundRestoresBalance() {
        Refunded r = spendAcceptance("REF-001", true);
        assertThat(s.balance(r.member(), "PTS").balanceActive()).isEqualTo(2200);
    }

    @Order(2)
    @Test
    @DisplayName("[TB-WAL-REF-002] rimborso: un movimento REFUND con l'importo speso e fatto wallet.points.refunded")
    void refundMovementAndFact() {
        Refunded r = spendAcceptance("REF-002", true);
        List<Map<String, Object>> refunds = s.ledger(r.member(), "REFUND");
        assertThat(refunds).hasSize(1);
        assertThat(num(refunds.getFirst().get("amount"))).isEqualTo(1500);
        assertThat(refunds.getFirst().get("direction")).isEqualTo("+");
        assertThat(refunds.getFirst().get("redemption_id")).isEqualTo(r.redemption());
        assertThat(num(refunds.getFirst().get("balance_after"))).isEqualTo(2200);
        JsonNode d = s.memberFacts("wallet.points.refunded", r.member()).getFirst().path("data");
        assertThat(d.path("redemptionId").asString()).isEqualTo(r.redemption());
        assertThat(d.path("amount").asLong()).isEqualTo(1500);
        assertThat(d.path("balanceAfter").asLong()).isEqualTo(2200);
    }

    @Order(2)
    @Test
    @DisplayName("[TB-WAL-REF-003] rimborso: nuovo lotto con scadenza = la più lontana tra i lotti consumati (> oggi + 30 g)")
    void refundCreatesNewLotWithFarthestExpiry() {
        Refunded r = spendAcceptance("REF-003", true);
        List<Map<String, Object>> fresh = s.allLots(r.member()).stream()
                .filter(l -> !r.lots().containsValue((String) l.get("id"))).toList();
        assertThat(fresh).as("docs/03 §4.2: il rimborso crea un nuovo lotto").hasSize(1);
        assertThat(num(fresh.getFirst().get("amount"))).isEqualTo(1500);
        assertThat(num(fresh.getFirst().get("remaining"))).isEqualTo(1500);
        assertThat(fresh.getFirst().get("status")).isEqualTo("ACTIVE");
        assertThat(instant(fresh.getFirst().get("expires_at"))).isEqualTo(endOfDay("2027-03-31"));
        assertThat(num(s.lotRow(r.lots().get("A")).get("remaining"))).isZero();
        assertThat(num(s.lotRow(r.lots().get("B")).get("remaining"))).isZero();
        assertThat(num(s.lotRow(r.lots().get("C")).get("remaining"))).isEqualTo(700);
    }

    @Order(2)
    @Test
    @DisplayName("[TB-WAL-REF-004] rimborso da lotto che scade prima di oggi + 30 g: nuovo lotto con scadenza oggi + 30 g")
    void refundExpiryAtLeastThirtyDays() {
        String m = freshMember("REF-004");
        s.member(m, "BASE", 0, "ACTIVE");
        String a = s.lot(m, "PTS", 300, "ACTIVE", NOW.minus(300, ChronoUnit.DAYS), null, NOW.plus(10, ChronoUnit.DAYS));
        String rdm = freshId("RDM");
        payments.spend(WalTestbook.redemptionRequested(m, rdm, 200));
        payments.refund(WalTestbook.redemptionCancelled(m, rdm, 200, true));
        List<Map<String, Object>> fresh = s.allLots(m).stream().filter(l -> !a.equals(l.get("id"))).toList();
        assertThat(fresh).as("docs/03 §4.2: il rimborso crea un nuovo lotto").hasSize(1);
        Instant exp = instant(fresh.getFirst().get("expires_at"));
        Instant min = NOW.plus(30, ChronoUnit.DAYS);
        assertThat(exp).isBetween(min, WalTestbook.startOfNextDay(min.atZone(WalTestbook.ROME).toLocalDate()));
    }

    @Order(2)
    @Test
    @DisplayName("[TB-WAL-REF-005] rimborso di una spesa che ha consumato parte di un lotto: nuovo lotto con la scadenza di quel lotto")
    void refundOfPartialLotSpend() {
        String m = freshMember("REF-005");
        s.member(m, "BASE", 0, "ACTIVE");
        String a = s.lot(m, "PTS", 500, "ACTIVE", rome("2025-12-10T10:00:00"), null, endOfDay("2026-12-31"));
        String rdm = freshId("RDM");
        payments.spend(WalTestbook.redemptionRequested(m, rdm, 200));
        payments.refund(WalTestbook.redemptionCancelled(m, rdm, 200, true));
        List<Map<String, Object>> fresh = s.allLots(m).stream().filter(l -> !a.equals(l.get("id"))).toList();
        assertThat(fresh).as("docs/03 §4.2: il rimborso crea un nuovo lotto").hasSize(1);
        assertThat(num(fresh.getFirst().get("amount"))).isEqualTo(200);
        assertThat(instant(fresh.getFirst().get("expires_at"))).isEqualTo(endOfDay("2026-12-31"));
        assertThat(num(s.lotRow(a).get("remaining"))).isEqualTo(300);
    }

    /** Lotto di 500 in scadenza il 31 ottobre, spesa di 300, scadenza del residuo il 1 novembre, rimborso il 2 novembre. */
    private String refundAfterOriginExpired(String row) {
        String m = freshMember(row);
        s.member(m, "BASE", 0, "ACTIVE");
        s.lot(m, "PTS", 500, "ACTIVE", rome("2025-10-10T10:00:00"), null, endOfDay("2026-10-31"));
        String rdm = freshId("RDM");
        payments.spend(WalTestbook.redemptionRequested(m, rdm, 300));
        wallet.expirePoints(rome("2026-11-01T00:00:00"));
        CLOCK.set(rome("2026-11-02T10:00:00"));
        payments.refund(WalTestbook.redemptionCancelled(m, rdm, 300, true));
        return m;
    }

    @Order(2)
    @Test
    @DisplayName("[TB-WAL-REF-006] rimborso dopo la scadenza del lotto d'origine: nuovo lotto con scadenza oggi + 30 g")
    void refundAfterOriginExpiredLotExpiry() {
        String m = refundAfterOriginExpired("REF-006");
        List<Map<String, Object>> active = s.allLots(m).stream().filter(l -> "ACTIVE".equals(l.get("status"))).toList();
        assertThat(active).hasSize(1);
        Instant exp = instant(active.getFirst().get("expires_at"));
        Instant min = rome("2026-11-02T10:00:00").plus(30, ChronoUnit.DAYS);
        assertThat(exp).as("max(31/10/2026, oggi + 30 g) = %s; ottenuto %s", min, WalTestbook.describe(exp))
                .isBetween(min, WalTestbook.startOfNextDay(min.atZone(WalTestbook.ROME).toLocalDate()));
    }

    @Order(2)
    @Test
    @DisplayName("[TB-WAL-REF-007] rimborso dopo la scadenza del lotto d'origine: i punti spesi tornano tutti nel saldo")
    void refundAfterOriginExpiredBalance() {
        String m = refundAfterOriginExpired("REF-007");
        assertThat(s.balance(m, "PTS").balanceActive()).isEqualTo(300);
        assertThat(s.activeLotsSum(m, "PTS")).isEqualTo(300);
    }

    @Order(2)
    @Test
    @DisplayName("[TB-WAL-REF-008] annullo con rimborso ripetuto: un solo REFUND")
    void refundIdempotent() {
        Refunded r = spendAcceptance("REF-008", true);
        payments.refund(WalTestbook.redemptionCancelled(r.member(), r.redemption(), 1500, true));
        assertThat(s.ledger(r.member(), "REFUND")).hasSize(1);
        assertThat(s.balance(r.member(), "PTS").balanceActive()).isEqualTo(2200);
    }

    @Order(2)
    @Test
    @DisplayName("[TB-WAL-REF-009] annullo con refund=false: nessun rimborso")
    void cancelWithoutRefund() {
        Refunded r = spendAcceptance("REF-009", false);
        payments.refund(WalTestbook.redemptionCancelled(r.member(), r.redemption(), 1500, false));
        assertThat(s.ledger(r.member(), "REFUND")).isEmpty();
        assertThat(s.balance(r.member(), "PTS").balanceActive()).isEqualTo(700);
    }

    @Order(2)
    @Test
    @DisplayName("[TB-WAL-REF-010] annullo con rimborso di una richiesta mai spesa: nessun movimento")
    void refundWithoutSpend() {
        String m = freshMember("REF-010");
        s.member(m, "BASE", 0, "ACTIVE");
        prepareLots(m, "A:500:ACTIVE:2025-12-10:2026-12-31");
        payments.refund(WalTestbook.redemptionCancelled(m, freshId("RDM"), 200, true));
        assertThat(s.ledger(m)).isEmpty();
        assertThat(s.balance(m, "PTS").balanceActive()).isEqualTo(500);
    }

    @Order(2)
    @Test
    @DisplayName("[TB-WAL-REF-011] dopo il rimborso: saldo attivo = Σ lotti ACTIVE")
    void refundKeepsInvariant() {
        Refunded r = spendAcceptance("REF-011", true);
        assertThat(s.activeLotsSum(r.member(), "PTS")).isEqualTo(s.balance(r.member(), "PTS").balanceActive());
    }

    // =====================================================================================================
    // §14 Rettifiche manuali
    // =====================================================================================================

    @Order(3)
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/wal/adjustments.csv", numLinesToSkip = 1)
    void adjustments(String id, String description, String role, String currency, String direction, String amountExpr,
                     String reason, String noteToken, String status, long active, long pending, int httpStatus, String code) {
        String m = freshMember(id);
        if (!"NEW".equals(status)) {
            s.member(m, "BASE", 0, status);
            if (active > 0) {
                s.lot(m, "PTS", active, "ACTIVE", NOW.minus(30, ChronoUnit.DAYS), null, endOfDay("2027-08-31"));
            }
            if (pending > 0) {
                s.lot(m, "PTS", pending, "PENDING", NOW.minus(1, ChronoUnit.DAYS), NOW.plus(5, ChronoUnit.DAYS), endOfDay("2027-09-30"));
            }
        }
        long amount = switch (amountExpr) {
            case "BAL" -> active;
            case "BAL+1" -> active + 1;
            case "BAL-1" -> active - 1;
            default -> Long.parseLong(amountExpr);
        };
        String note = note(noteToken);
        ObjectNode body = MAPPER.createObjectNode();
        putOrNull(body, "currency", currency);
        putOrNull(body, "direction", direction);
        body.put("amount", amount);
        putOrNull(body, "reason", reason);
        if (note != null) {
            body.put("note", note);
        }

        // Righe 031, 032 — TESTBOOK: ambiguo, vedi TB-WAL-ADJ-031, TB-WAL-ADJ-032
        // Riga 042 — Q-147 DECISA: membro senza wallet → 404, nessun wallet creato
        WalItSupport.Resp r = s.http(HttpMethod.POST, "/v1/wallets/" + m + "/adjustments", role, body);

        assertThat(r.status()).as("%s: stato HTTP (%s)", id, r.body()).isEqualTo(httpStatus);
        if (!"-".equals(code)) {
            assertThat(r.code()).as("%s: codice d'errore", id).isEqualTo(code);
        }
        if (httpStatus != 200) {
            assertThat(s.ledger(m)).as("%s: nessun movimento", id).isEmpty();
            assertThat(s.balance(m, "PTS").balanceActive()).isEqualTo(active);
            assertThat(s.memberFacts("wallet.points.adjusted", m)).isEmpty();
            if ("NEW".equals(status)) {
                assertThat(wallets.findByMember(m)).as("%s: nessun wallet creato", id).isEmpty();
            }
            return;
        }
        boolean credit = "CREDIT".equals(direction);
        long after = credit ? active + amount : active - amount;
        assertThat(r.body().path("balanceAfter").asLong()).isEqualTo(after);
        assertThat(s.balance(m, "PTS").balanceActive()).as("%s: saldo attivo", id).isEqualTo(after);
        assertThat(s.balance(m, "PTS").balancePending()).isEqualTo(pending);
        List<Map<String, Object>> entries = s.ledger(m, credit ? "ADJUST_CREDIT" : "ADJUST_DEBIT");
        assertThat(entries).hasSize(1);
        Map<String, Object> e = entries.getFirst();
        assertThat(num(e.get("amount"))).isEqualTo(amount);
        assertThat(e.get("direction")).isEqualTo(credit ? "+" : "-");
        assertThat(e.get("source_type")).isEqualTo("MANUAL");
        assertThat(e.get("actor")).isEqualTo(WalItSupport.actorHeader(role));
        JsonNode meta = MAPPER.readTree(e.get("metadata").toString());
        assertThat(meta.path("reason").asString()).isEqualTo(reason);
        assertThat(meta.path("note").asString()).isEqualTo(note.trim());
        if (credit) {
            List<Map<String, Object>> created = s.allLots(m).stream()
                    .filter(l -> e.get("id").equals(l.get("ledger_entry_id"))).toList();
            assertThat(created).as("%s: l'accredito crea un lotto", id).hasSize(1);
            assertThat(created.getFirst().get("status")).isEqualTo("ACTIVE");
            assertThat(num(created.getFirst().get("remaining"))).isEqualTo(amount);
            assertThat(created.getFirst().get("expires_at")).as("%s: scadenza da policy PTS", id).isNotNull();
        } else {
            long consumed = s.consumptions((String) e.get("id")).stream().mapToLong(c -> num(c.get("amount"))).sum();
            assertThat(consumed).isEqualTo(amount);
        }
        assertThat(s.activeLotsSum(m, "PTS")).as("%s: saldo = Σ lotti attivi", id).isEqualTo(after);
        JsonNode d = s.memberFacts("wallet.points.adjusted", m).getFirst().path("data");
        assertThat(d.path("direction").asString()).isEqualTo(direction);
        assertThat(d.path("currency").asString()).isEqualTo("PTS");
        assertThat(d.path("amount").asLong()).isEqualTo(amount);
        assertThat(d.path("reason").asString()).isEqualTo(reason);
        assertThat(d.path("balanceAfter").asLong()).isEqualTo(after);
        assertThat(s.audit("wallet:" + m + ":PTS")).as("%s: voce di audit ADJUST", id)
                .anyMatch(a -> "ADJUST".equals(a.path("data").path("action").asString()));
    }

    @Order(3)
    @Test
    @DisplayName("[TB-WAL-ADJ-043] addebito manuale su più lotti: consuma per scadenza crescente (AMBIGUO)")
    void debitConsumesByExpiry() {
        // TESTBOOK: ambiguo, vedi TB-WAL-ADJ-043 (docs/03 §4.2 definisce il FIFO per la spesa, non per l'addebito)
        String m = freshMember("ADJ-043");
        s.member(m, "BASE", 0, "ACTIVE");
        String dec = s.lot(m, "PTS", 700, "ACTIVE", rome("2025-12-10T10:00:00"), null, endOfDay("2026-12-31"));
        String oct = s.lot(m, "PTS", 300, "ACTIVE", rome("2025-10-10T10:00:00"), null, endOfDay("2026-10-31"));
        ObjectNode body = MAPPER.createObjectNode();
        body.put("currency", "PTS");
        body.put("direction", "DEBIT");
        body.put("amount", 400);
        body.put("reason", "CORRECTION");
        body.put("note", "Correzione di prova testbook");
        assertThat(s.http(HttpMethod.POST, "/v1/wallets/" + m + "/adjustments", "CARE", body).status()).isEqualTo(200);
        assertThat(num(s.lotRow(oct).get("remaining"))).isZero();
        assertThat(num(s.lotRow(dec).get("remaining"))).isEqualTo(600);
    }

    private static void putOrNull(ObjectNode body, String field, String value) {
        if (!"NULL".equals(value)) {
            body.put(field, value);
        }
    }

    /** {@code LEN:n} lettere, {@code UNICODE:n} lettere accentate, {@code SPACES:n}, {@code PAD:n} (n lettere tra spazi), {@code EMPTY}, {@code NULL}. */
    private static String note(String token) {
        if ("NULL".equals(token)) {
            return null;
        }
        if ("EMPTY".equals(token)) {
            return "";
        }
        String[] p = token.split(":");
        int n = Integer.parseInt(p[1]);
        return switch (p[0]) {
            case "LEN" -> "n".repeat(n);
            case "UNICODE" -> "àèìòùéçñüö".repeat(n / 10 + 1).substring(0, n);
            case "SPACES" -> " ".repeat(n);
            case "PAD" -> "     " + "p".repeat(n) + "     ";
            default -> throw new IllegalArgumentException(token);
        };
    }
}
