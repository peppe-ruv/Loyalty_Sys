package io.loyaltyhub.wallet;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.wallet.application.TierAdminService;
import io.loyaltyhub.wallet.application.WalletService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * wallet-service M1.4 (docs/servizi/wallet-service.md §7): applica gli effetti punti con il moltiplicatore
 * di tier, idempotenza su {@code effect_id}, wallet "on the fly". Col profilo {@code demo} valute, livelli e
 * saldi iniziali sono caricati dai seed. Senza Docker: EmbeddedKafka + Zonky.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WalletServiceIT {

    private static final String FACTS = "lh.facts.v1";
    private static final EmbeddedPostgres PG = startPg();

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private WalletService walletService;

    @Autowired
    private TierAdminService tierAdmin;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=wallet");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    void ptsGrantForSilverAppliesTierMultiplier() {
        // Effetto PTS 130 con tierMultiplierApplies per un membro SILVER (×1,25) → EARN 162.
        // MBR-000002 resta SILVER (nessun test lo fa salire), a differenza di 003 usato per la salita.
        long before = balance("MBR-000002", "PTS");
        publishGrant("EFF-WD-01", "MBR-000002", "PTS", 130, true, "CMP-PURCHASE-BASE");

        JsonNode earned = awaitEarned("EFF-WD-01");
        assertThat(earned.path("amount").asLong()).isEqualTo(162);
        assertThat(earned.path("tierCode").asString()).isEqualTo("SILVER");
        assertThat(earned.path("balanceAfter").asLong()).isEqualTo(before + 162);

        JsonNode wallet = wallet("MBR-000002");
        assertThat(wallet.path("balances").path("PTS").path("active").asLong()).isEqualTo(before + 162);
    }

    @Test
    void stsAccrualNotMultipliedAndCrossingThresholdUpgradesTier() {
        // Giulia (MBR-000003, SILVER, 2 880 STS) + 130 STS = 3 010 ≥ 3 000 → GOLD nello stesso commit
        // (docs/servizi/wallet-service.md §7). Lo STS non è mai moltiplicato dal tier.
        long before = balance("MBR-000003", "STS");
        publishGrant("EFF-STS-01", "MBR-000003", "STS", 130, false, "CMP-PURCHASE-BASE");

        JsonNode earned = awaitEarned("EFF-STS-01");
        assertThat(earned.path("amount").asLong()).isEqualTo(130);
        assertThat(balance("MBR-000003", "STS")).isEqualTo(before + 130);

        JsonNode upgraded = awaitFact("io.loyaltyhub.fact.tier.upgraded",
                d -> d.path("previousTier").asString().equals("SILVER")
                        && d.path("newTier").asString().equals("GOLD"));
        assertThat(upgraded).isNotNull();
        assertThat(upgraded.path("periodSts").asLong()).isEqualTo(3010);

        JsonNode wallet = wallet("MBR-000003");
        assertThat(wallet.path("tier").path("code").asString()).isEqualTo("GOLD");
        assertThat(wallet.path("tier").path("multiplier").asDouble()).isEqualTo(1.5);
    }

    @Test
    void sameEffectIdIsIdempotent() {
        long before = balance("MBR-000005", "PTS");
        publishGrant("EFF-DUP-01", "MBR-000005", "PTS", 100, false, "CMP-WELCOME");
        publishGrant("EFF-DUP-01", "MBR-000005", "PTS", 100, false, "CMP-WELCOME");

        awaitEarned("EFF-DUP-01");
        // Attende ed è certo che un secondo accredito non avvenga: il saldo cresce di 100 una sola volta.
        sleep();
        assertThat(balance("MBR-000005", "PTS")).isEqualTo(before + 100);
    }

    @Test
    void grantForUnknownMemberCreatesWalletOnTheFly() {
        String newMember = "MBR-009999";
        publishGrant("EFF-NEW-01", newMember, "PTS", 50, false, "CMP-WELCOME");

        JsonNode earned = awaitEarned("EFF-NEW-01");
        assertThat(earned.path("amount").asLong()).isEqualTo(50); // BASE ×1,00, tierMultiplierApplies=false
        assertThat(balance(newMember, "PTS")).isEqualTo(50);
    }

    @Test
    void ptsGrantCreatesLotWithRollingExpiry() {
        // Effetto PTS con tempo 2026-09-15 e policy ROLLING_MONTHS(12) → scadenza fine mese +12 = 2027-09-30.
        publishGrant("EFF-LOT-01", "MBR-000007", "PTS", 200, false, "CMP-PURCHASE-BASE");
        awaitEarned("EFF-LOT-01");

        JsonNode lots = client().get().uri("/v1/wallets/MBR-000007/lots").retrieve().body(JsonNode.class);
        JsonNode granted = null;
        for (JsonNode lot : lots) {
            if (lot.path("amount").asLong() == 200 && lot.path("status").asString().equals("ACTIVE")
                    && lot.path("expiresAt").asString("").startsWith("2027-09-30")) {
                granted = lot;
            }
        }
        assertThat(granted).as("lotto PTS con scadenza rolling +12 mesi").isNotNull();
        assertThat(granted.path("remaining").asLong()).isEqualTo(200);
    }

    @Test
    void pendingGrantIsHeldThenReleased() {
        String member = "MBR-009100";
        // pendingDays > 0 → lotto PENDING: il saldo attivo resta 0, i punti sono in attesa.
        publishGrant("EFF-PEND-01", member, "PTS", 300, false, "CMP-WELCOME", 5);
        JsonNode earned = awaitEarned("EFF-PEND-01");
        assertThat(earned.path("pending").asBoolean()).isTrue();

        JsonNode before = wallet(member);
        assertThat(before.path("balances").path("PTS").path("active").asLong()).isZero();
        assertThat(before.path("balances").path("PTS").path("pending").asLong()).isEqualTo(300);

        // Rilascio con asOf oltre availableAt (2026-09-15 + 5g): il lotto passa ad ACTIVE.
        walletService.releasePending(Instant.parse("2027-01-01T00:00:00Z"));

        JsonNode released = awaitFact("io.loyaltyhub.fact.wallet.points.released",
                d -> d.path("currency").asString().equals("PTS") && d.path("amount").asLong() == 300
                        && d.path("balanceAfter").asLong() == 300);
        assertThat(released).isNotNull();

        JsonNode after = wallet(member);
        assertThat(after.path("balances").path("PTS").path("active").asLong()).isEqualTo(300);
        assertThat(after.path("balances").path("PTS").path("pending").asLong()).isZero();
    }

    @Test
    void walletViewExposesExpiringSoon() {
        // Il seeder crea per ogni membro con PTS ≥ 100 una quota (30%) in scadenza entro 12 giorni.
        JsonNode wallet = wallet("MBR-000004");
        JsonNode soon = wallet.path("expiringSoon");
        assertThat(soon.path("within30d").asBoolean()).isTrue();
        assertThat(soon.path("amount").asLong()).isEqualTo(12300 * 30 / 100);
        assertThat(soon.path("nextExpiryAt").isNull()).isFalse();
    }

    @Test
    void expiredLotIsRemovedFromBalance() {
        // Accredito con business-time nel passato → scadenza rolling +12 = fine 09/2026 (isolato dagli altri lotti).
        String member = "MBR-009200";
        publishGrant("EFF-EXP-01", member, "PTS", 400, false, "CMP-PURCHASE-BASE", 0, "2025-09-10T10:00:00Z");
        awaitEarned("EFF-EXP-01");
        assertThat(wallet(member).path("balances").path("PTS").path("active").asLong()).isEqualTo(400);

        // Job scadenze con asOf oltre la scadenza del lotto (ma prima delle scadenze dei lotti seed).
        walletService.expirePoints(java.time.Instant.parse("2026-09-30T23:00:00Z"));

        JsonNode expired = awaitFact("io.loyaltyhub.fact.wallet.points.expired",
                d -> d.path("currency").asString().equals("PTS") && d.path("amount").asLong() == 400
                        && d.path("balanceAfter").asLong() == 0);
        assertThat(expired).isNotNull();
        assertThat(wallet(member).path("balances").path("PTS").path("active").asLong()).isZero();
    }

    @Test
    void expiringLotIsWarnedOncePerLot() {
        // Lotto in scadenza a fine 10/2026: dentro la finestra di preavviso (asOf → +30 giorni).
        String member = "MBR-009300";
        publishGrant("EFF-WARN-01", member, "PTS", 333, false, "CMP-PURCHASE-BASE", 0, "2025-10-10T10:00:00Z");
        awaitEarned("EFF-WARN-01");

        WalletService.JobOutcome outcome = walletService.expiryWarnings(java.time.Instant.parse("2026-10-05T00:00:00Z"));
        assertThat(outcome.lots()).isGreaterThanOrEqualTo(1);

        JsonNode expiring = awaitFact("io.loyaltyhub.fact.wallet.points.expiring",
                d -> d.path("currency").asString().equals("PTS") && d.path("amount").asLong() == 333);
        assertThat(expiring).isNotNull();
        assertThat(expiring.path("expiresAt").asString("")).isNotBlank();
    }

    @Test
    void liabilityMatchesWalletsAndSplitsActiveLotsByExpiryMonth() {
        JsonNode pts = client().get().uri("/v1/liability?currency=PTS").retrieve().body(JsonNode.class);
        assertThat(pts.path("currency").asString()).isEqualTo("PTS");
        long outstanding = pts.path("outstanding").asLong();
        assertThat(outstanding).isPositive();

        long byMonth = 0;
        String previous = "";
        for (JsonNode m : pts.path("byExpiryMonth")) {
            byMonth += m.path("amount").asLong();
            if (!m.path("month").isNull()) {
                assertThat(m.path("month").asString()).matches("\\d{4}-\\d{2}").isGreaterThan(previous);
                previous = m.path("month").asString();
            }
        }
        // Invariante dei lotti (docs/03 §4.2): i lotti attivi coprono esattamente i saldi attivi (stesso snapshot).
        assertThat(byMonth).isEqualTo(outstanding);
        // Il lotto seed "in scadenza a breve" (now + 12 giorni) cade nel mese corrente o nel successivo.
        String soon = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Rome")).plusDays(12).toString().substring(0, 7);
        assertThat(pts.path("byExpiryMonth").findValuesAsString("month")).contains(soon);

        int unknown = client().get().uri("/v1/liability?currency=XYZ")
                .exchange((req, res) -> res.getStatusCode().value());
        assertThat(unknown).isEqualTo(404);
    }

    @Test
    void tierDistributionAndHistoryAreExposed() {
        JsonNode dist = client().get().uri("/v1/tiers/distribution").retrieve().body(JsonNode.class);
        assertThat(dist.size()).isEqualTo(4);
        long total = 0;
        for (JsonNode t : dist) {
            total += t.path("members").asLong();
        }
        assertThat(total).isGreaterThanOrEqualTo(12); // i 12 membri seed

        JsonNode history = client().get().uri("/v1/members/MBR-000004/tier-history").retrieve().body(JsonNode.class);
        assertThat(history.size()).isGreaterThanOrEqualTo(1);
        assertThat(history.get(history.size() - 1).path("toTier").asString()).isEqualTo("GOLD"); // INITIAL seed
    }

    @Test
    void tierUpdateRejectsNonMonotonicThresholds() {
        // SILVER a 5 000 supererebbe GOLD (3 000) → violazione della monotonìa.
        assertThatThrownBy(() -> tierAdmin.update("SILVER",
                new TierAdminService.TierUpdate(null, 5000L, null, null, null, null)))
                .isInstanceOf(LhException.class)
                .satisfies(e -> assertThat(((LhException) e).code()).isEqualTo("TIER_THRESHOLDS_NOT_MONOTONIC"));

        // BASE deve restare a 0.
        assertThatThrownBy(() -> tierAdmin.update("BASE",
                new TierAdminService.TierUpdate(null, 100L, null, null, null, null)))
                .isInstanceOf(LhException.class);
    }

    @Test
    void tiersAndWalletViewAreExposed() {
        JsonNode tiers = client().get().uri("/v1/tiers").retrieve().body(JsonNode.class);
        assertThat(tiers.size()).isEqualTo(4);

        JsonNode wallet = wallet("MBR-000004");
        assertThat(wallet.path("tier").path("code").asString()).isEqualTo("GOLD");
        assertThat(wallet.path("tier").path("multiplier").asDouble()).isEqualTo(1.5);
    }

    // ---------- helper ----------

    private long balance(String memberId, String currency) {
        try {
            JsonNode wallet = wallet(memberId);
            return wallet.path("balances").path(currency).path("active").asLong();
        } catch (Exception e) {
            return 0; // wallet non ancora esistente
        }
    }

    private JsonNode wallet(String memberId) {
        return client().get().uri("/v1/wallets/" + memberId).retrieve().body(JsonNode.class);
    }

    private JsonNode awaitEarned(String effectId) {
        try (KafkaConsumer<String, String> consumer = consumer("earn-" + effectId)) {
            consumer.subscribe(List.of(FACTS));
            ConsumerRecord<String, String> rec = poll(consumer, r -> {
                JsonNode e = readJson(r.value());
                return e.path("type").asString().equals("io.loyaltyhub.fact.wallet.points.earned")
                        && e.path("data").path("effectId").asString().equals(effectId);
            });
            assertThat(rec).as("fatto wallet.points.earned per " + effectId).isNotNull();
            return readJson(rec.value()).path("data");
        }
    }

    private JsonNode awaitFact(String type, Predicate<JsonNode> dataMatch) {
        try (KafkaConsumer<String, String> consumer = consumer("fact-" + type + "-" + System.nanoTime())) {
            consumer.subscribe(List.of(FACTS));
            ConsumerRecord<String, String> rec = poll(consumer, r -> {
                JsonNode e = readJson(r.value());
                return e.path("type").asString().equals(type) && dataMatch.test(e.path("data"));
            });
            return rec == null ? null : readJson(rec.value()).path("data");
        }
    }

    private void publishGrant(String effectId, String memberId, String currency, long amount,
                             boolean tierApplies, String campaignCode) {
        publishGrant(effectId, memberId, currency, amount, tierApplies, campaignCode, 0);
    }

    private void publishGrant(String effectId, String memberId, String currency, long amount,
                             boolean tierApplies, String campaignCode, int pendingDays) {
        publishGrant(effectId, memberId, currency, amount, tierApplies, campaignCode, pendingDays,
                "2026-09-15T10:15:00Z");
    }

    private void publishGrant(String effectId, String memberId, String currency, long amount,
                             boolean tierApplies, String campaignCode, int pendingDays, String time) {
        Map<String, Object> data = Map.of(
                "effectId", effectId, "campaignCode", campaignCode, "actionId", "ACT-" + effectId,
                "actionType", "purchase.completed", "currency", currency, "baseAmount", amount,
                "campaignMultiplier", 1.0, "amount", amount, "tierMultiplierApplies", tierApplies,
                "pendingDays", pendingDays);
        Map<String, Object> event = Map.of(
                "specversion", "1.0", "id", "EV-" + effectId, "source", "urn:loyaltyhub:service:campaign",
                "type", "io.loyaltyhub.effect.points.grant", "subject", "member:" + memberId,
                "time", time, "lhcorrelationid", "ACT-" + effectId, "lhhop", 0, "data", data);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class))) {
            producer.send(new ProducerRecord<>("lh.effects.v1", memberId, mapper.writeValueAsString(event))).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private KafkaConsumer<String, String> consumer(String group) {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, System.getProperty("spring.embedded.kafka.brokers"),
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
    }

    private ConsumerRecord<String, String> poll(KafkaConsumer<String, String> consumer,
                                                Predicate<ConsumerRecord<String, String>> match) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(400))) {
                if (match.test(r)) {
                    return r;
                }
            }
        }
        return null;
    }

    private JsonNode readJson(String value) {
        return mapper.readTree(value);
    }

    /** Attesa fissa per provare che qualcosa NON accade (accredito duplicato): non va accorciata. */
    private static void sleep() {
        try {
            Thread.sleep(2000);
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
