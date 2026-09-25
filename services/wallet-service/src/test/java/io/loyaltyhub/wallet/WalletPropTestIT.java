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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WalletPropTestIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final ZoneId ROME = ZoneId.of("Europe/Rome");

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private MutableClock mutableClock;

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

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        public Clock mutableClock() {
            return new MutableClock(Instant.parse("2026-01-01T10:00:00Z"));
        }
    }

    static class MutableClock extends Clock {
        private Instant instant;
        public MutableClock(Instant instant) { this.instant = instant; }
        public void setInstant(Instant instant) { this.instant = instant; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
        @Override public Instant instant() { return instant; }
    }

    private static EmbeddedPostgres startPg() {
        try { return EmbeddedPostgres.builder().start(); } catch (Exception e) { throw new RuntimeException(e); }
    }

    // --- Model ---
    static class ModelLot {
        String id;
        String currency;
        long amount;
        long remaining;
        Instant earnedAt;
        Instant expiresAt;
        String status;

        public ModelLot(String id, String currency, long amount, Instant earnedAt, Instant expiresAt) {
            this.id = id;
            this.currency = currency;
            this.amount = amount;
            this.remaining = amount;
            this.earnedAt = earnedAt;
            this.expiresAt = expiresAt;
            this.status = "ACTIVE";
        }
    }

    static class ModelTier {
        String code;
        long thresholdSts;
        double multiplier;
        public ModelTier(String code, long thresholdSts, double multiplier) {
            this.code = code; this.thresholdSts = thresholdSts; this.multiplier = multiplier;
        }
    }

    class WalletModel {
        List<ModelLot> lots = new ArrayList<>();
        String currentTier = "BASE";
        List<ModelTier> tiersList;
        long periodSts = 0;

        public WalletModel(List<ModelTier> tiers) { this.tiersList = tiers; }

        public long activeBalance(String currency) {
            return lots.stream().filter(l -> l.currency.equals(currency) && "ACTIVE".equals(l.status)).mapToLong(l -> l.remaining).sum();
        }

        public void grantPts(String id, long amount, Instant time, Instant expiresAt) {
            lots.add(new ModelLot(id, "PTS", amount, time, expiresAt));
        }

        public void grantSts(String id, long amount, Instant time) {
            if (amount > 0) {
                lots.add(new ModelLot(id, "STS", amount, time, null));
                periodSts += amount;
                updateTier();
            }
        }

        private void updateTier() {
            String newTier = currentTier;
            for (ModelTier t : tiersList) {
                if (periodSts >= t.thresholdSts && getRank(t.code) > getRank(newTier)) {
                    newTier = t.code;
                }
            }
            currentTier = newTier;
        }

        private int getRank(String code) {
            for (int i = 0; i < tiersList.size(); i++) {
                if (tiersList.get(i).code.equals(code)) return i;
            }
            return 0;
        }

        public void expire(Instant now) {
            for (ModelLot l : lots) {
                if ("ACTIVE".equals(l.status) && l.expiresAt != null && !now.isBefore(l.expiresAt)) {
                    l.status = "EXPIRED";
                    l.remaining = 0;
                }
            }
        }

        public boolean spend(long amount) {
            if (activeBalance("PTS") < amount) return false;

            List<ModelLot> activePts = new ArrayList<>();
            for (ModelLot l : lots) {
                if ("PTS".equals(l.currency) && "ACTIVE".equals(l.status) && l.remaining > 0) {
                    activePts.add(l);
                }
            }
            activePts.sort(Comparator.comparing((ModelLot l) -> l.expiresAt, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(l -> l.earnedAt)
                    .thenComparing(l -> l.id)); // tie break by ID or earnedAt

            long toConsume = amount;
            for (ModelLot l : activePts) {
                if (toConsume == 0) break;
                long taken = Math.min(l.remaining, toConsume);
                l.remaining -= taken;
                toConsume -= taken;
                if (l.remaining == 0) l.status = "EXHAUSTED";
            }
            return true;
        }

        public void adjustCredit(String id, String currency, long amount, Instant now, Instant expiresAt) {
            lots.add(new ModelLot(id, currency, amount, now, expiresAt));
            if ("STS".equals(currency)) {
                periodSts += amount;
                updateTier();
            }
        }

        public boolean adjustDebit(String currency, long amount) {
            if (activeBalance(currency) < amount) return false;

            List<ModelLot> active = new ArrayList<>();
            for (ModelLot l : lots) {
                if (currency.equals(l.currency) && "ACTIVE".equals(l.status) && l.remaining > 0) {
                    active.add(l);
                }
            }
            active.sort(Comparator.comparing((ModelLot l) -> l.expiresAt, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(l -> l.earnedAt)
                    .thenComparing(l -> l.id));

            long toConsume = amount;
            for (ModelLot l : active) {
                if (toConsume == 0) break;
                long taken = Math.min(l.remaining, toConsume);
                l.remaining -= taken;
                toConsume -= taken;
                if (l.remaining == 0) l.status = "EXHAUSTED";
            }
            return true;
        }
    }

    // --- Property Based Test ---
    @Test
    void testWalletProperties() throws Exception {
        String seedStr = System.getProperty("prop.seed");
        long seed;
        if (seedStr != null && !seedStr.isBlank()) {
            seed = Long.parseLong(seedStr);
        } else {
            seed = 123456789L; // deterministic for easy debugging
        }
        System.out.println("Property-based test running with seed: " + seed);

        Random random = new Random(seed);

        JsonNode tiersJson = client().get().uri("/v1/tiers").retrieve().body(JsonNode.class);
        List<ModelTier> modelTiers = new ArrayList<>();
        for (JsonNode t : tiersJson) {
            modelTiers.add(new ModelTier(t.path("code").asString(), t.path("thresholdSts").asLong(), t.path("multiplier").asDouble()));
        }
        modelTiers.sort(Comparator.comparingDouble(t -> t.multiplier));

        int sequences = 100;
        int minOps = 20;
        int maxOps = 60;

        for (int i = 0; i < sequences; i++) {
            runSequence("MBR-PROP-" + UUID.randomUUID(), random, minOps, maxOps, modelTiers);
        }
    }

    private void runSequence(String memberId, Random random, int minOps, int maxOps, List<ModelTier> tiers) throws Exception {
        WalletModel model = new WalletModel(tiers);
        int ops = random.nextInt(maxOps - minOps + 1) + minOps;

        mutableClock.setInstant(Instant.parse("2026-01-01T10:00:00Z"));
        publishRegistered(memberId);
        awaitWalletCreation(memberId);

        List<String> opLog = new ArrayList<>();

        for (int j = 0; j < ops; j++) {
            int opType = random.nextInt(100);
            String stepDesc = "";

            if (opType < 40) { // 40% GRANT PTS (simple)
                long amount = random.nextInt(1000) + 1;
                String effectId = UUID.randomUUID().toString().substring(0, 8);
                Instant time = mutableClock.instant();
                Instant expiresAt = time.atZone(ROME).plusMonths(12).with(TemporalAdjusters.lastDayOfMonth()).with(LocalTime.MAX).toInstant();

                stepDesc = "GRANT_PTS " + amount;

                model.grantPts(effectId, amount, time, expiresAt);
                publishGrant(effectId, memberId, "PTS", amount, false, 0, time.toString());

                awaitFact("io.loyaltyhub.fact.wallet.points.earned", d -> d.path("effectId").asString().equals(effectId));

            } else if (opType < 55) { // 15% GRANT STS
                long amount = random.nextInt(500) + 1;
                String effectId = UUID.randomUUID().toString().substring(0, 8);
                Instant time = mutableClock.instant();
                stepDesc = "GRANT_STS " + amount;

                model.grantSts(effectId, amount, time);
                publishGrant(effectId, memberId, "STS", amount, false, 0, time.toString());

                awaitFact("io.loyaltyhub.fact.wallet.points.earned", d -> d.path("effectId").asString().equals(effectId));

            } else if (opType < 70) { // 15% SPEND
                long amount = random.nextInt((int) Math.max(1, model.activeBalance("PTS") + 100)) + 1;
                String redemptionId = "RDM-" + UUID.randomUUID().toString().substring(0, 8);
                stepDesc = "SPEND " + amount;

                boolean couldSpend = model.spend(amount);
                publishRequested(memberId, redemptionId, amount, mutableClock.instant());

                if (couldSpend) {
                    awaitFact("io.loyaltyhub.fact.wallet.points.spent", d -> d.path("redemptionId").asString().equals(redemptionId));
                } else {
                    awaitFact("io.loyaltyhub.fact.wallet.spend.rejected", d -> d.path("redemptionId").asString().equals(redemptionId));
                }

            } else if (opType < 75) { // 5% ADJUST_CREDIT PTS
                long amount = random.nextInt(500) + 1;
                String reason = "GOODWILL";
                Instant expiresAt = mutableClock.instant().atZone(ROME).plusMonths(12).with(TemporalAdjusters.lastDayOfMonth()).with(LocalTime.MAX).toInstant();
                stepDesc = "ADJUST_CREDIT " + amount;

                model.adjustCredit("adj-"+UUID.randomUUID().toString().substring(0,4), "PTS", amount, mutableClock.instant(), expiresAt);

                client().post().uri("/v1/wallets/" + memberId + "/adjustments")
                    .header("X-LH-Actor", "ADMIN:admin")
                    .body(Map.of("currency", "PTS", "direction", "CREDIT", "amount", amount, "reason", reason, "note", "Test adjustment from prop test"))
                    .retrieve().toBodilessEntity();

                awaitFact("io.loyaltyhub.fact.wallet.points.adjusted", d -> true);

            } else if (opType < 80) { // 5% ADJUST_DEBIT PTS
                if (model.activeBalance("PTS") > 0) {
                    long amount = random.nextInt((int) model.activeBalance("PTS")) + 1;
                    stepDesc = "ADJUST_DEBIT " + amount;

                    model.adjustDebit("PTS", amount);

                    try {
                        client().post().uri("/v1/wallets/" + memberId + "/adjustments")
                            .header("X-LH-Actor", "ADMIN:admin")
                            .body(Map.of("currency", "PTS", "direction", "DEBIT", "amount", amount, "reason", "CORRECTION", "note", "Test adjustment from prop test"))
                            .retrieve().toBodilessEntity();
                        awaitFact("io.loyaltyhub.fact.wallet.points.adjusted", d -> true);
                    } catch(Exception e) {}
                }

            } else if (opType < 95) { // 15% JUMP TIME & EXPIRE
                int jumpDays = random.nextInt(40) + 1;
                Instant newTime = mutableClock.instant().plus(Duration.ofDays(jumpDays));

                int specialJump = random.nextInt(10);
                if (specialJump == 0) {
                    newTime = mutableClock.instant().atZone(ROME).with(TemporalAdjusters.lastDayOfMonth()).with(LocalTime.MAX).toInstant();
                } else if (specialJump == 1) {
                    newTime = LocalDate.of(2028, 2, 29).atTime(LocalTime.of(12, 0)).atZone(ROME).toInstant();
                } else if (specialJump == 2) {
                    LocalDate march = LocalDate.of(mutableClock.instant().atZone(ROME).getYear(), 3, 1).with(TemporalAdjusters.lastInMonth(java.time.DayOfWeek.SUNDAY));
                    newTime = march.atTime(LocalTime.of(3, 0)).atZone(ROME).toInstant();
                } else if (specialJump == 3) {
                    LocalDate oct = LocalDate.of(mutableClock.instant().atZone(ROME).getYear(), 10, 1).with(TemporalAdjusters.lastInMonth(java.time.DayOfWeek.SUNDAY));
                    newTime = oct.atTime(LocalTime.of(3, 0)).atZone(ROME).toInstant();
                }

                if (newTime.isBefore(mutableClock.instant())) {
                    newTime = mutableClock.instant().plus(Duration.ofDays(1));
                }
                mutableClock.setInstant(newTime);
                stepDesc = "JUMP_TIME_AND_EXPIRE at " + newTime;

                model.expire(mutableClock.instant());

                client().post().uri("/v1/demo/jobs/expire-points?asOf=" + mutableClock.instant().toString())
                    .header("X-LH-Actor", "ADMIN:admin")
                    .retrieve().toBodilessEntity();

            } else { // 5% RUN_WARNING
                stepDesc = "RUN_WARNING at " + mutableClock.instant();
                client().post().uri("/v1/demo/jobs/expiry-warnings?asOf=" + mutableClock.instant().toString())
                    .header("X-LH-Actor", "ADMIN:admin")
                    .retrieve().toBodilessEntity();
            }

            if (!stepDesc.isBlank()) {
                opLog.add(stepDesc);
            }

            try {
                JsonNode wallet = client().get().uri("/v1/wallets/" + memberId).retrieve().body(JsonNode.class);
                long srvPts = wallet.path("balances").path("PTS").path("active").asLong();

                if (srvPts != model.activeBalance("PTS")) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("=== DIVERGENCE ===\n");
                    sb.append("Operations:\n");
                    for (int o = 0; o < opLog.size(); o++) {
                        sb.append(o).append(": ").append(opLog.get(o)).append("\n");
                    }
                    sb.append("srvPts=").append(srvPts).append(" modelActivePts=").append(model.activeBalance("PTS")).append("\n");

                    JsonNode srvLots = client().get().uri("/v1/wallets/" + memberId + "/lots").retrieve().body(JsonNode.class);
                    sb.append("Service Lots: ").append(srvLots).append("\n");
                    sb.append("Model Lots:\n");
                    for (ModelLot l : model.lots) {
                        sb.append(l.currency).append(" ").append(l.amount).append(" rem=").append(l.remaining)
                          .append(" status=").append(l.status).append(" exp=").append(l.expiresAt).append(" earned=").append(l.earnedAt).append("\n");
                    }
                    System.err.println("DIVERGENCE! " + sb.toString());
                    throw new AssertionError("Divergence");
                }

                assertThat(srvPts).as("Balance mismatch at op %d", j).isEqualTo(model.activeBalance("PTS"));

                long srvSts = wallet.path("balances").path("STS").path("lifetimeEarned").asLong();
                assertThat(srvSts).as("STS lifetime mismatch").isEqualTo(model.periodSts);

                assertThat(srvPts).isGreaterThanOrEqualTo(0);

                JsonNode srvLots = client().get().uri("/v1/wallets/" + memberId + "/lots").retrieve().body(JsonNode.class);
                long lotsSum = 0;
                for (JsonNode l : srvLots) {
                    if ("PTS".equals(l.path("currency").asText()) && "ACTIVE".equals(l.path("status").asText())) {
                        lotsSum += l.path("remaining").asLong();
                    }
                }
                assertThat(lotsSum).as("Active lots sum mismatch with balance").isEqualTo(srvPts);

                String srvTier = wallet.path("tier").path("code").asText();
                assertThat(srvTier).as("Tier mismatch").isEqualTo(model.currentTier);

            } catch (AssertionError e) {
                System.err.println("ASSERT FAILED during op " + j + ": " + stepDesc);
                e.printStackTrace();
                throw e;
            }
        }
    }

    private void awaitWalletCreation(String memberId) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                client().get().uri("/v1/wallets/" + memberId).retrieve().toBodilessEntity();
                return;
            } catch (Exception e) {
                Thread.sleep(100);
            }
        }
        throw new AssertionError("Wallet not created in time");
    }

    private void publishRegistered(String memberId) {
        send("lh.facts.v1", memberId, Map.of(
            "specversion", "1.0", "id", "REG-" + memberId,
            "source", "urn:loyaltyhub:service:member", "type", "io.loyaltyhub.fact.member.registered",
            "subject", "member:" + memberId, "time", "2026-01-01T09:00:00Z", "lhcorrelationid", "REG", "lhhop", 0,
            "data", Map.of("memberId", memberId, "status", "ACTIVE")
        ));
    }

    private void publishGrant(String effectId, String memberId, String currency, long amount,
                             boolean tierApplies, int pendingDays, String time) {
        Map<String, Object> data = Map.of(
                "effectId", effectId, "campaignCode", "CMP-PROP", "actionId", "ACT-" + effectId,
                "actionType", "purchase.completed", "currency", currency, "baseAmount", amount,
                "campaignMultiplier", 1.0, "amount", amount, "tierMultiplierApplies", tierApplies,
                "pendingDays", pendingDays);
        send("lh.effects.v1", memberId, Map.of(
                "specversion", "1.0", "id", "EV-" + effectId, "source", "urn:loyaltyhub:service:campaign",
                "type", "io.loyaltyhub.effect.points.grant", "subject", "member:" + memberId,
                "time", time, "lhcorrelationid", "ACT-" + effectId, "lhhop", 0, "data", data));
    }

    private void publishRequested(String memberId, String redemptionId, long cost, Instant time) {
        send("lh.facts.v1", memberId, Map.of(
            "specversion", "1.0", "id", "REQ-" + redemptionId + "-" + System.nanoTime(),
            "source", "urn:loyaltyhub:service:reward", "type", "io.loyaltyhub.fact.reward.redemption.requested",
            "subject", "member:" + memberId, "time", time.toString(), "lhcorrelationid", "REQ-" + redemptionId,
            "lhhop", 0, "data", Map.of("redemptionId", redemptionId, "rewardCode", "RWD-IT", "rewardName", "Prop Reward",
                    "currency", "PTS", "pointsCost", cost)
        ));
    }

    private void send(String topic, String key, Map<String, Object> event) {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class))) {
            producer.send(new ProducerRecord<>(topic, key, mapper.writeValueAsString(event))).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private JsonNode awaitFact(String type, Predicate<JsonNode> dataMatch) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "group.id", "prop-it-" + System.nanoTime(), "auto.offset.reset", "earliest",
                "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class))) {
            consumer.subscribe(List.of("lh.facts.v1"));
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> rec : consumer.poll(Duration.ofMillis(300))) {
                    JsonNode e = mapper.readTree(rec.value());
                    if (e.path("type").asString().equals(type) && dataMatch.test(e.path("data"))) {
                        return e;
                    }
                }
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        throw new AssertionError("nessun " + type);
    }
}
