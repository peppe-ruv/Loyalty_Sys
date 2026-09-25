package io.loyaltyhub.wallet.testbook;

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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.loyaltyhub.wallet.testbook.WalTestbook.MAPPER;
import static io.loyaltyhub.wallet.testbook.WalTestbook.freshId;
import static io.loyaltyhub.wallet.testbook.WalTestbook.freshMember;
import static io.loyaltyhub.wallet.testbook.WalTestbook.grant;
import static io.loyaltyhub.wallet.testbook.WalTestbook.rome;
import static io.loyaltyhub.wallet.testbook.WalTestbook.sts;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-WAL (docs/testbook/TB-WAL-wallet.md) — configurazione del programma (valute, livelli, edizioni) e chiusura
 * dell'edizione con anteprima. Cambia stato globale (policy, soglie, edizioni): contesto proprio, ordine esplicito,
 * valori del seed ripristinati dopo ogni modifica accettata.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TestbookWalAdminIT {

    static final Instant NOW = Instant.parse("2026-09-24T08:00:00Z");
    static final WalTestbook.MutableClock CLOCK = new WalTestbook.MutableClock(NOW);
    static final Instant T0 = rome("2026-09-18T12:15:00");
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
    // §15 Valute: policy di scadenza (vale per i nuovi lotti)
    // =====================================================================================================

    private void restorePts() {
        ObjectNode body = MAPPER.createObjectNode();
        body.set("expiryPolicy", WalTestbook.seedPolicy("PTS"));
        assertThat(s.http(HttpMethod.PUT, "/v1/currencies/PTS", "ADMIN", body).status()).isEqualTo(200);
    }

    private static JsonNode policy(String token) {
        String[] p = token.split(":");
        ObjectNode n = MAPPER.createObjectNode();
        switch (p[0]) {
            case "ROLLING" -> {
                n.put("type", "ROLLING_MONTHS");
                n.put("months", Integer.parseInt(p[1]));
            }
            case "EOEG" -> {
                n.put("type", "END_OF_EDITION_PLUS_GRACE");
                if (p.length > 1) {
                    n.put("graceDays", Integer.parseInt(p[1]));
                }
            }
            default -> n.put("type", p[0]);
        }
        return n;
    }

    @Order(1)
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/wal/currency-policy.csv", numLinesToSkip = 1)
    void currencyPolicy(String id, String description, String actor, String code, String token, int httpStatus,
                        String expectedExpiry) {
        ObjectNode body = MAPPER.createObjectNode();
        body.set("expiryPolicy", policy(token));
        // Righe 006, 007, 009 — TESTBOOK: ambiguo, vedi TB-WAL-CUR-006, TB-WAL-CUR-007, TB-WAL-CUR-009
        WalItSupport.Resp r = s.http(HttpMethod.PUT, "/v1/currencies/" + code, actor, body);
        try {
            assertThat(r.status()).as("%s: stato HTTP (%s)", id, r.body()).isEqualTo(httpStatus);
            if (httpStatus != 200) {
                return;
            }
            String m = freshMember(id);
            s.member(m, "BASE", 0, "ACTIVE");
            wallet.applyGrant(grant(m, freshId("EFF"), "PTS", 100, false, 0, T0));
            JsonNode d = s.memberFacts("wallet.points.earned", m).getFirst().path("data");
            if ("NULL".equals(expectedExpiry)) {
                assertThat(d.hasNonNull("expiresAt")).as("%s: il nuovo lotto non scade", id).isFalse();
            } else {
                Instant exp = Instant.parse(d.path("expiresAt").asString());
                assertThat(WalTestbook.isLastInstantOf(exp, LocalDate.parse(expectedExpiry)))
                        .as("%s: scadenza del nuovo lotto %s", id, WalTestbook.describe(exp)).isTrue();
            }
        } finally {
            if (r.status() == 200) {
                restorePts();
            }
        }
    }

    @Order(1)
    @Test
    @DisplayName("[TB-WAL-CUR-002] cambio di policy: i lotti già esistenti conservano la loro scadenza")
    void policyChangeKeepsExistingLots() {
        String m = freshMember("CUR-002");
        s.member(m, "BASE", 0, "ACTIVE");
        wallet.applyGrant(grant(m, freshId("EFF"), "PTS", 100, false, 0, T0));
        Object before = s.allLots(m).getFirst().get("expires_at");
        ObjectNode body = MAPPER.createObjectNode();
        body.set("expiryPolicy", policy("ROLLING:3"));
        assertThat(s.http(HttpMethod.PUT, "/v1/currencies/PTS", "ADMIN", body).status()).isEqualTo(200);
        try {
            assertThat(s.allLots(m).getFirst().get("expires_at")).isEqualTo(before);
        } finally {
            restorePts();
        }
    }

    @Order(1)
    @Test
    @DisplayName("[TB-WAL-CUR-014] modifica della policy di una valuta: voce di audit UPDATE")
    void policyChangeAudited() {
        ObjectNode body = MAPPER.createObjectNode();
        body.set("expiryPolicy", policy("ROLLING:12"));
        Instant since = Instant.now().minusSeconds(1);
        assertThat(s.http(HttpMethod.PUT, "/v1/currencies/PTS", "ADMIN", body).status()).isEqualTo(200);
        assertThat(s.audit("currency:PTS")).anyMatch(a -> "UPDATE".equals(a.path("data").path("action").asString()));
        assertThat(s.countAuditSince("UPDATE", since)).isGreaterThanOrEqualTo(1);
        restorePts();
    }

    // =====================================================================================================
    // §16 Livelli: soglie crescenti, BASE a 0, ruoli
    // =====================================================================================================

    private void restoreTier(String code) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("thresholdSts", WalTestbook.threshold(code));
        body.put("multiplier", WalTestbook.multiplier(code));
        assertThat(s.http(HttpMethod.PUT, "/v1/tiers/" + code, "ADMIN", body).status()).isEqualTo(200);
    }

    @Order(2)
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/wal/tier-admin.csv", numLinesToSkip = 1)
    void tierAdmin(String id, String description, String actor, String code, String threshold, String multiplier,
                   int httpStatus, String errorCode) {
        ObjectNode body = MAPPER.createObjectNode();
        if (!"-".equals(threshold)) {
            body.put("thresholdSts", sts(threshold));
        }
        if (!"-".equals(multiplier)) {
            body.put("multiplier", new BigDecimal(multiplier));
        }
        // Riga 015 — TESTBOOK: ambiguo, vedi TB-WAL-TAD-015
        WalItSupport.Resp r = s.http(HttpMethod.PUT, "/v1/tiers/" + code, actor, body);
        try {
            assertThat(r.status()).as("%s: stato HTTP (%s)", id, r.body()).isEqualTo(httpStatus);
            if (!"-".equals(errorCode)) {
                assertThat(r.code()).isEqualTo(errorCode);
            }
            JsonNode scale = s.get("/v1/tiers").body();
            for (JsonNode t : scale) {
                boolean changed = httpStatus == 200 && t.path("code").asString().equals(code);
                long exp = changed && !"-".equals(threshold) ? sts(threshold) : WalTestbook.threshold(t.path("code").asString());
                assertThat(t.path("thresholdSts").asLong()).as("%s: soglia di %s", id, t.path("code").asString()).isEqualTo(exp);
            }
        } finally {
            if (r.status() == 200) {
                restoreTier(code);
            }
        }
    }

    @Order(2)
    @Test
    @DisplayName("[TB-WAL-TAD-017] nuovo moltiplicatore SILVER: il successivo accredito PTS lo applica")
    void newMultiplierAppliesToNextGrant() {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("multiplier", new BigDecimal("1.75"));
        assertThat(s.http(HttpMethod.PUT, "/v1/tiers/SILVER", "ADMIN", body).status()).isEqualTo(200);
        try {
            String m = freshMember("TAD-017");
            s.member(m, "SILVER", WalTestbook.threshold("SILVER"), "ACTIVE");
            wallet.applyGrant(grant(m, freshId("EFF"), "PTS", 130, true, 0, T0));
            assertThat(s.balance(m, "PTS").balanceActive()).isEqualTo(227); // floor(130 × 1,75)
        } finally {
            restoreTier("SILVER");
        }
    }

    @Order(2)
    @Test
    @DisplayName("[TB-WAL-TAD-018] modifica di un livello: voce di audit UPDATE")
    void tierChangeAudited() {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("name", "Silver");
        assertThat(s.http(HttpMethod.PUT, "/v1/tiers/SILVER", "ADMIN", body).status()).isEqualTo(200);
        assertThat(s.audit("tier:SILVER")).anyMatch(a -> "UPDATE".equals(a.path("data").path("action").asString()));
    }

    // =====================================================================================================
    // §17 Edizioni: creazione e modifica senza sovrapposizioni
    // =====================================================================================================

    @Order(3)
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/wal/edition-crud.csv", numLinesToSkip = 1)
    void editionCrud(String id, String description, String actor, String method, String code, String name,
                     String startDate, String endDate, String grace, int httpStatus) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("code", code);
        if (!"NULL".equals(name)) {
            body.put("name", name);
        }
        if (!"NULL".equals(startDate)) {
            body.put("startDate", startDate);
        }
        body.put("endDate", endDate);
        if (!"-".equals(grace)) {
            body.put("redemptionGraceUntil", grace);
        }
        String path = "POST".equals(method) ? "/v1/editions" : "/v1/editions/" + code;
        // Righe 007, 010 — TESTBOOK: ambiguo, vedi TB-WAL-EDN-007, TB-WAL-EDN-010
        WalItSupport.Resp r = s.http(HttpMethod.valueOf(method), path, actor, body);
        assertThat(r.status()).as("%s: stato HTTP (%s)", id, r.body()).isEqualTo(httpStatus);
        if (httpStatus == 200) {
            JsonNode e = edition(code);
            assertThat(e.path("startDate").asString()).isEqualTo(startDate);
            assertThat(e.path("endDate").asString()).isEqualTo(endDate);
            if ("POST".equals(method)) {
                assertThat(e.path("status").asString()).as("%s: nuova edizione PLANNED", id).isEqualTo("PLANNED");
            }
        }
        assertThat(overlaps()).as("%s: nessuna sovrapposizione tra edizioni", id).isEmpty();
    }

    @Order(3)
    @Test
    @DisplayName("[TB-WAL-EDN-016] creazione di un'edizione: voce di audit CREATE")
    void editionCreateAudited() {
        assertThat(s.audit("edition:ED-2028")).anyMatch(a -> "CREATE".equals(a.path("data").path("action").asString()));
    }

    private JsonNode edition(String code) {
        for (JsonNode e : s.get("/v1/editions").body()) {
            if (code.equals(e.path("code").asString())) {
                return e;
            }
        }
        return MAPPER.createObjectNode();
    }

    private List<String> overlaps() {
        List<JsonNode> eds = new ArrayList<>();
        s.get("/v1/editions").body().forEach(eds::add);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < eds.size(); i++) {
            for (int j = i + 1; j < eds.size(); j++) {
                LocalDate s1 = LocalDate.parse(eds.get(i).path("startDate").asString());
                LocalDate e1 = LocalDate.parse(eds.get(i).path("endDate").asString());
                LocalDate s2 = LocalDate.parse(eds.get(j).path("startDate").asString());
                LocalDate e2 = LocalDate.parse(eds.get(j).path("endDate").asString());
                if (!e1.isBefore(s2) && !e2.isBefore(s1)) {
                    out.add(eds.get(i).path("code").asString() + "/" + eds.get(j).path("code").asString());
                }
            }
        }
        return out;
    }

    // =====================================================================================================
    // §9 Chiusura dell'edizione: anteprima, applicazione, esiti, stati
    // =====================================================================================================

    /** Membri della chiusura: livello attuale, periodSts e stato; preparati una volta sola. */
    private final Map<String, String> closeMembers = new java.util.LinkedHashMap<>();

    private String cm(String key) {
        return closeMembers.get(key);
    }

    private void prepareCloseMembers() {
        if (!closeMembers.isEmpty()) {
            return;
        }
        String[][] specs = {
                {"G0", "GOLD", "0", "ACTIVE"},
                {"P7", "PLATINUM", "PLATINUM", "ACTIVE"},
                {"S9", "SILVER", "SILVER-1", "ACTIVE"},
                {"B0", "BASE", "0", "ACTIVE"},
                {"GB", "GOLD", "500", "BLOCKED"},
        };
        for (String[] sp : specs) {
            String m = freshMember("ECL-" + sp[0]);
            s.member(m, sp[1], sts(sp[2]), sp[3]);
            closeMembers.put(sp[0], m);
        }
    }

    private JsonNode preview(String actor) {
        WalItSupport.Resp r = s.http(HttpMethod.POST, "/v1/editions/ED-2026/close?dryRun=true", actor, null);
        assertThat(r.status()).isEqualTo(200);
        return r.body();
    }

    private static JsonNode entry(JsonNode preview, String memberId) {
        for (JsonNode e : preview.path("members")) {
            if (memberId.equals(e.path("memberId").asString())) {
                return e;
            }
        }
        return null;
    }

    @Order(4)
    @Test
    @DisplayName("[TB-WAL-ECL-001] anteprima: per ogni membro attivo tier attuale, STS, guadagnato, nuovo ed esito secondo la regola")
    void previewEntries() {
        prepareCloseMembers();
        JsonNode p = preview("ADMIN");
        String[][] exp = {
                {"G0", "GOLD", "0", "BASE", "SILVER", "DOWNGRADED"},
                {"P7", "PLATINUM", "PLATINUM", "PLATINUM", "PLATINUM", "RETAINED"},
                {"S9", "SILVER", "SILVER-1", "BASE", "BASE", "DOWNGRADED"},
                {"B0", "BASE", "0", "BASE", "BASE", "RETAINED"},
        };
        for (String[] e : exp) {
            JsonNode en = entry(p, cm(e[0]));
            assertThat(en).as("membro %s in anteprima", e[0]).isNotNull();
            assertThat(en.path("currentTier").asString()).isEqualTo(e[1]);
            assertThat(en.path("periodSts").asLong()).isEqualTo(sts(e[2]));
            assertThat(en.path("earnedTier").asString()).isEqualTo(e[3]);
            assertThat(en.path("newTier").asString()).isEqualTo(e[4]);
            assertThat(en.path("outcome").asString()).isEqualTo(e[5]);
        }
    }

    @Order(4)
    @Test
    @DisplayName("[TB-WAL-ECL-002] anteprima 2026: Stefano (MBR-000006, GOLD, 650 STS) → SILVER (guadagnato BASE, pavimento SILVER)")
    void previewStefano() {
        JsonNode en = entry(preview("ADMIN"), "MBR-000006");
        assertThat(en).isNotNull();
        assertThat(en.path("currentTier").asString()).isEqualTo("GOLD");
        assertThat(en.path("periodSts").asLong()).isEqualTo(650);
        assertThat(en.path("earnedTier").asString()).isEqualTo("BASE");
        assertThat(en.path("newTier").asString()).isEqualTo("SILVER");
        assertThat(en.path("outcome").asString()).isEqualTo("DOWNGRADED");
    }

    @Order(4)
    @Test
    @DisplayName("[TB-WAL-ECL-003] anteprima: il riepilogo conta gli esiti dell'elenco")
    void previewSummary() {
        JsonNode p = preview("ADMIN");
        long retained = 0;
        long downgraded = 0;
        for (JsonNode e : p.path("members")) {
            if ("RETAINED".equals(e.path("outcome").asString())) {
                retained++;
            } else if ("DOWNGRADED".equals(e.path("outcome").asString())) {
                downgraded++;
            }
        }
        assertThat(p.path("summary").path("retained").asLong()).isEqualTo(retained);
        assertThat(p.path("summary").path("downgraded").asLong()).isEqualTo(downgraded);
        assertThat(retained + downgraded).isEqualTo(p.path("members").size());
    }

    @Order(4)
    @Test
    @DisplayName("[TB-WAL-ECL-004] anteprima: i membri non ACTIVE non sono considerati")
    void previewExcludesInactive() {
        prepareCloseMembers();
        assertThat(entry(preview("ADMIN"), cm("GB"))).isNull();
    }

    @Order(4)
    @Test
    @DisplayName("[TB-WAL-ECL-005] anteprima: nessuna scrittura (livelli, STS, stato edizione, fatti)")
    void previewWritesNothing() {
        prepareCloseMembers();
        preview("ADMIN");
        assertThat(s.tier(cm("G0")).tierCode()).isEqualTo("GOLD");
        assertThat(s.tier(cm("P7")).periodSts()).isEqualTo(WalTestbook.threshold("PLATINUM"));
        assertThat(edition("ED-2026").path("status").asString()).isEqualTo("ACTIVE");
        assertThat(s.memberFacts("tier.downgraded", cm("G0"))).isEmpty();
        assertThat(s.memberFacts("tier.retained", cm("P7"))).isEmpty();
        assertThat(s.facts("edition.closed", "edition:ED-2026")).isEmpty();
    }

    @Order(4)
    @Test
    @DisplayName("[TB-WAL-ECL-006] anteprima con ruolo ANALYST: consentita (AMBIGUO)")
    void previewAnalyst() {
        // TESTBOOK: ambiguo, vedi TB-WAL-ECL-006 (solo l'applicazione è riservata ad ADMIN)
        preview("ANALYST");
    }

    @Order(5)
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/wal/close-roles.csv", numLinesToSkip = 1)
    void applyForbidden(String id, String description, String actor) {
        prepareCloseMembers();
        WalItSupport.Resp r = s.http(HttpMethod.POST, "/v1/editions/ED-2026/close?dryRun=false", actor, null);
        assertThat(r.status()).as("%s", id).isEqualTo(403);
        assertThat(r.code()).isEqualTo("FORBIDDEN_ROLE");
        assertThat(edition("ED-2026").path("status").asString()).isEqualTo("ACTIVE");
        assertThat(s.tier(cm("G0")).tierCode()).isEqualTo("GOLD");
    }

    private JsonNode applied;
    private JsonNode previewBeforeApply;

    @Order(6)
    @Test
    @DisplayName("[TB-WAL-ECL-012] applicazione con ADMIN: stesso riepilogo dell'anteprima sugli stessi dati")
    void applyAdmin() {
        prepareCloseMembers();
        previewBeforeApply = preview("ADMIN");
        WalItSupport.Resp r = s.http(HttpMethod.POST, "/v1/editions/ED-2026/close?dryRun=false", "ADMIN", null);
        assertThat(r.status()).isEqualTo(200);
        applied = r.body();
        assertThat(applied.path("summary")).isEqualTo(previewBeforeApply.path("summary"));
    }

    @Order(7)
    @Test
    @DisplayName("[TB-WAL-ECL-013] membro retrocesso: nuovo livello, STS azzerati, storico DOWNGRADE, fatto tier.downgraded")
    void appliedDowngrade() {
        String m = cm("G0");
        assertThat(s.tier(m).tierCode()).isEqualTo("SILVER");
        assertThat(s.tier(m).periodSts()).isZero();
        JsonNode h = lastHistory(m);
        assertThat(h.path("kind").asString()).isEqualTo("DOWNGRADE");
        assertThat(h.path("fromTier").asString()).isEqualTo("GOLD");
        assertThat(h.path("toTier").asString()).isEqualTo("SILVER");
        assertThat(h.path("editionCode").asString()).isEqualTo("ED-2026");
        List<JsonNode> f = s.memberFacts("tier.downgraded", m);
        assertThat(f).hasSize(1);
        assertThat(f.getFirst().path("data").path("previousTier").asString()).isEqualTo("GOLD");
        assertThat(f.getFirst().path("data").path("newTier").asString()).isEqualTo("SILVER");
        assertThat(f.getFirst().path("data").path("editionCode").asString()).isEqualTo("ED-2026");
    }

    @Order(7)
    @Test
    @DisplayName("[TB-WAL-ECL-014] membro confermato: stesso livello, STS azzerati, storico RETAIN, fatto tier.retained")
    void appliedRetain() {
        String m = cm("P7");
        assertThat(s.tier(m).tierCode()).isEqualTo("PLATINUM");
        assertThat(s.tier(m).periodSts()).isZero();
        assertThat(lastHistory(m).path("kind").asString()).isEqualTo("RETAIN");
        List<JsonNode> f = s.memberFacts("tier.retained", m);
        assertThat(f).hasSize(1);
        assertThat(f.getFirst().path("data").path("tier").asString()).isEqualTo("PLATINUM");
        assertThat(f.getFirst().path("data").path("editionCode").asString()).isEqualTo("ED-2026");
    }

    @Order(7)
    @Test
    @DisplayName("[TB-WAL-ECL-015] membro BLOCKED: non toccato dalla chiusura (livello, STS, nessun fatto)")
    void appliedSkipsBlocked() {
        String m = cm("GB");
        assertThat(s.tier(m).tierCode()).isEqualTo("GOLD");
        assertThat(s.tier(m).periodSts()).isEqualTo(500);
        assertThat(s.memberFacts("tier.downgraded", m)).isEmpty();
        assertThat(s.memberFacts("tier.retained", m)).isEmpty();
    }

    @Order(7)
    @Test
    @DisplayName("[TB-WAL-ECL-016] dopo la chiusura: ED-2026 CLOSED e una sola edizione ACTIVE")
    void appliedEditionStatus() {
        assertThat(edition("ED-2026").path("status").asString()).isEqualTo("CLOSED");
        long active = 0;
        for (JsonNode e : s.get("/v1/editions").body()) {
            if ("ACTIVE".equals(e.path("status").asString())) {
                active++;
            }
        }
        assertThat(active).isEqualTo(1);
    }

    @Order(7)
    @Test
    @DisplayName("[TB-WAL-ECL-017] dopo la chiusura: diventa ACTIVE la successiva (ED-2027), anche con un'edizione PLANNED precedente (ED-2024)")
    void appliedNextEditionActive() {
        assertThat(edition("ED-2024").path("status").asString()).as("ED-2024 creata PLANNED (TB-WAL-EDN-005)").isNotEmpty();
        assertThat(edition("ED-2027").path("status").asString()).as("la successiva a ED-2026 (docs/03 §4.3)").isEqualTo("ACTIVE");
    }

    @Order(7)
    @Test
    @DisplayName("[TB-WAL-ECL-018] fatto edition.closed con i conteggi del riepilogo")
    void appliedEditionClosedFact() {
        List<JsonNode> f = s.facts("edition.closed", "edition:ED-2026");
        assertThat(f).hasSize(1);
        JsonNode d = f.getFirst().path("data");
        assertThat(d.path("editionCode").asString()).isEqualTo("ED-2026");
        assertThat(d.path("retained").asLong()).isEqualTo(applied.path("summary").path("retained").asLong());
        assertThat(d.path("downgraded").asLong()).isEqualTo(applied.path("summary").path("downgraded").asLong());
    }

    @Order(7)
    @Test
    @DisplayName("[TB-WAL-ECL-019] chiusura dell'edizione: voce di audit TRANSITION")
    void appliedAudited() {
        assertThat(s.audit("edition:ED-2026")).anyMatch(a -> "TRANSITION".equals(a.path("data").path("action").asString()));
    }

    @Order(8)
    @Test
    @DisplayName("[TB-WAL-ECL-020] seconda chiusura di ED-2026: rifiutata (422), niente discese doppie")
    void closeTwice() {
        WalItSupport.Resp r = s.http(HttpMethod.POST, "/v1/editions/ED-2026/close?dryRun=false", "ADMIN", null);
        assertThat(r.status()).isEqualTo(422);
        assertThat(s.tier(cm("G0")).tierCode()).isEqualTo("SILVER");
        assertThat(s.memberFacts("tier.downgraded", cm("G0"))).hasSize(1);
    }

    @Order(8)
    @Test
    @DisplayName("[TB-WAL-ECL-021] anteprima della chiusura di un'edizione già chiusa: rifiutata (422)")
    void previewClosed() {
        assertThat(s.http(HttpMethod.POST, "/v1/editions/ED-2026/close?dryRun=true", "ADMIN", null).status()).isEqualTo(422);
    }

    @Order(8)
    @Test
    @DisplayName("[TB-WAL-ECL-022] chiusura di un'edizione PLANNED: rifiutata (422)")
    void closePlanned() {
        assertThat(s.http(HttpMethod.POST, "/v1/editions/ED-2028/close?dryRun=false", "ADMIN", null).status()).isEqualTo(422);
    }

    @Order(8)
    @Test
    @DisplayName("[TB-WAL-ECL-023] chiusura di un'edizione sconosciuta: 404")
    void closeUnknown() {
        assertThat(s.http(HttpMethod.POST, "/v1/editions/ED-1999/close?dryRun=true", "ADMIN", null).status()).isEqualTo(404);
    }

    @Order(8)
    @Test
    @DisplayName("[TB-WAL-ECL-024] dopo la chiusura gli STS ripartono da 0 nella nuova edizione")
    void stsAfterClose() {
        String m = cm("G0");
        wallet.applyGrant(grant(m, freshId("EFF"), "STS", 100, false, 0, NOW));
        assertThat(s.tier(m).periodSts()).isEqualTo(100);
        assertThat(s.tier(m).tierCode()).isEqualTo("SILVER");
    }

    private JsonNode lastHistory(String memberId) {
        JsonNode h = s.get("/v1/members/" + memberId + "/tier-history").body();
        JsonNode last = null;
        for (JsonNode e : h) {
            if (last == null || e.path("at").asString().compareTo(last.path("at").asString()) >= 0) {
                last = e;
            }
        }
        return last;
    }
}
