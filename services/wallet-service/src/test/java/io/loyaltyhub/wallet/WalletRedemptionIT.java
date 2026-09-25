package io.loyaltyhub.wallet;

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
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4.3 saga di richiesta premio, lato wallet (docs/servizi/wallet-service.md §5, §7; F-WAL-04, F-WAL-08): spesa FIFO
 * sui lotti per scadenza, rifiuto per saldo insufficiente o membro non attivo, rimborso all'annullo con
 * {@code refund=true}, idempotenza su {@code redemption_id}. Senza Docker: EmbeddedKafka + Zonky.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WalletRedemptionIT {

    private static final EmbeddedPostgres PG = startPg();

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

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
    void spendConsumesLotsByExpiryAndRefundCreatesANewLot() throws Exception {
        // Lotti [500 scad. ott, 800 scad. dic, 900 scad. mar] (wallet-service.md §7), pubblicati in ordine inverso:
        // il FIFO è per scadenza, non per ordine di arrivo.
        String m = "MBR-IT-FIFO";
        grant(m, "EFF-FIFO-MAR", 900, "2026-03-10T10:00:00Z");
        grant(m, "EFF-FIFO-DEC", 800, "2025-12-10T10:00:00Z");
        grant(m, "EFF-FIFO-OCT", 500, "2025-10-10T10:00:00Z");
        awaitBalance(m, 2200);

        publishRequested(m, "RDM-IT-FIFO", 1500);
        JsonNode spent = awaitFact("io.loyaltyhub.fact.wallet.points.spent", d -> d.path("redemptionId").asString().equals("RDM-IT-FIFO"));
        assertThat(spent.path("data").path("amount").asLong()).isEqualTo(1500);
        assertThat(spent.path("data").path("balanceAfter").asLong()).isEqualTo(700);
        assertThat(spent.path("lhcorrelationid").asString()).isEqualTo("REQ-RDM-IT-FIFO");
        assertThat(remainingByAmount(m)).containsEntry(500L, 0L).containsEntry(800L, 0L).containsEntry(900L, 700L);
        JsonNode ledger = client().get().uri("/v1/wallets/" + m + "/ledger").retrieve().body(JsonNode.class);
        assertThat(ledger.toString()).contains("SPEND");

        // Stessa richiesta rielaborata (nuovo messaggio): nessuna seconda spesa.
        publishRequested(m, "RDM-IT-FIFO", 1500);
        long deadline = System.currentTimeMillis() + 1500;
        while (System.currentTimeMillis() < deadline) {
            if (balance(m) != 700) break;
            Thread.sleep(100);
        }
        assertThat(balance(m)).isEqualTo(700);

        // Annullo con rimborso (docs/03 §4.2): un lotto NUOVO da 1 500 con scadenza max(scadenza più lontana dei lotti
        // consumati = quella del lotto di marzo, oggi + 30 gg); i lotti d'origine restano consumati.
        String marchExpiry = lotExpiry(m, 900);
        publishCancelled(m, "RDM-IT-FIFO", 1500, true);
        JsonNode refunded = awaitFact("io.loyaltyhub.fact.wallet.points.refunded", d -> d.path("redemptionId").asString().equals("RDM-IT-FIFO"));
        assertThat(refunded.path("data").path("balanceAfter").asLong()).isEqualTo(2200);
        assertThat(remainingByAmount(m)).containsEntry(500L, 0L).containsEntry(800L, 0L).containsEntry(900L, 700L)
                .containsEntry(1500L, 1500L);
        assertThat(java.time.Instant.parse(marchExpiry)).as("scadenza del lotto di marzo oltre oggi + 30 gg")
                .isAfter(java.time.Instant.now().plus(Duration.ofDays(30)));
        assertThat(lotExpiry(m, 1500)).isEqualTo(marchExpiry);

        publishCancelled(m, "RDM-IT-FIFO", 1500, true);
        deadline = System.currentTimeMillis() + 1500;
        while (System.currentTimeMillis() < deadline) {
            if (balance(m) != 2200) break;
            Thread.sleep(100);
        }
        assertThat(balance(m)).as("rimborso idempotente").isEqualTo(2200);
    }

    @Test
    void insufficientBalanceOrInactiveMemberIsRejectedWithoutMovements() {
        // Anna (MBR-000001) ha 100 PTS.
        publishRequested("MBR-000001", "RDM-IT-POOR", 500);
        JsonNode rejected = awaitFact("io.loyaltyhub.fact.wallet.spend.rejected", d -> d.path("redemptionId").asString().equals("RDM-IT-POOR"));
        assertThat(rejected.path("data").path("reason").asString()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(rejected.path("data").path("available").asLong()).isEqualTo(100);
        assertThat(balance("MBR-000001")).isEqualTo(100);

        // Roberto (MBR-000008) è BLOCKED.
        publishRequested("MBR-000008", "RDM-IT-BLOCKED", 100);
        assertThat(awaitFact("io.loyaltyhub.fact.wallet.spend.rejected", d -> d.path("redemptionId").asString().equals("RDM-IT-BLOCKED"))
                .path("data").path("reason").asString()).isEqualTo("MEMBER_NOT_ACTIVE");
    }

    @Test
    void cancellationWithoutRefundOrWithoutSpendChangesNothing() throws Exception {
        long before = balance("MBR-000009");
        publishCancelled("MBR-000009", "RDM-IT-NEVER-SPENT", 500, true);
        publishCancelled("MBR-000009", "RDM-IT-NO-REFUND", 500, false);
        long deadline = System.currentTimeMillis() + 1500;
        while (System.currentTimeMillis() < deadline) {
            if (balance("MBR-000009") != before) break;
            Thread.sleep(100);
        }
        assertThat(balance("MBR-000009")).isEqualTo(before);
    }

    // ---------- helper ----------

    private String lotExpiry(String memberId, long amount) {
        for (JsonNode l : client().get().uri("/v1/wallets/" + memberId + "/lots").retrieve().body(JsonNode.class)) {
            if (l.path("amount").asLong() == amount) {
                return l.path("expiresAt").asString();
            }
        }
        throw new AssertionError("lotto da " + amount + " non trovato per " + memberId);
    }

    private Map<Long, Long> remainingByAmount(String memberId) {
        Map<Long, Long> out = new HashMap<>();
        JsonNode lots = client().get().uri("/v1/wallets/" + memberId + "/lots").retrieve().body(JsonNode.class);
        for (JsonNode l : lots) {
            out.put(l.path("amount").asLong(), l.path("remaining").asLong());
        }
        for (long amount : List.of(500L, 800L, 900L)) {
            out.putIfAbsent(amount, 0L); // i lotti esauriti possono non comparire tra quelli aperti
        }
        return out;
    }

    /** Saldo PTS attivo; 0 se il wallet non esiste ancora (il primo accredito lo crea in modo asincrono). */
    private long balance(String memberId) {
        return client().get().uri("/v1/portal/wallets/" + memberId).exchange((req, res) ->
                res.getStatusCode().value() == 404 ? 0L
                        : mapper.readTree(res.getBody()).path("balances").path("PTS").path("active").asLong());
    }

    private void awaitBalance(String memberId, long expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && balance(memberId) != expected) {
            Thread.sleep(200);
        }
        assertThat(balance(memberId)).isEqualTo(expected);
    }

    private JsonNode awaitFact(String type, Predicate<JsonNode> dataMatch) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "group.id", "it-" + System.nanoTime(), "auto.offset.reset", "earliest",
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
        }
        throw new AssertionError("nessun " + type);
    }

    private void grant(String memberId, String effectId, long amount, String time) {
        Map<String, Object> data = Map.of("effectId", effectId, "campaignCode", "CMP-IT", "actionId", "ACT-" + effectId,
                "actionType", "purchase.completed", "currency", "PTS", "baseAmount", amount, "campaignMultiplier", 1.0,
                "amount", amount, "tierMultiplierApplies", false, "pendingDays", 0);
        send("lh.effects.v1", memberId, Map.of("specversion", "1.0", "id", "EV-" + effectId,
                "source", "urn:loyaltyhub:service:campaign", "type", "io.loyaltyhub.effect.points.grant",
                "subject", "member:" + memberId, "time", time, "lhcorrelationid", "ACT-" + effectId, "lhhop", 0, "data", data));
    }

    private void publishRequested(String memberId, String redemptionId, long cost) {
        send("lh.facts.v1", memberId, Map.of("specversion", "1.0", "id", "REQ-" + redemptionId + "-" + System.nanoTime(),
                "source", "urn:loyaltyhub:service:reward", "type", "io.loyaltyhub.fact.reward.redemption.requested",
                "subject", "member:" + memberId, "time", "2026-09-20T10:00:00Z", "lhcorrelationid", "REQ-" + redemptionId,
                "lhhop", 0, "data", Map.of("redemptionId", redemptionId, "rewardCode", "RWD-IT", "rewardName", "Premio di prova",
                        "currency", "PTS", "pointsCost", cost)));
    }

    private void publishCancelled(String memberId, String redemptionId, long cost, boolean refund) {
        send("lh.facts.v1", memberId, Map.of("specversion", "1.0", "id", "CAN-" + redemptionId + "-" + System.nanoTime(),
                "source", "urn:loyaltyhub:service:reward", "type", "io.loyaltyhub.fact.reward.redemption.cancelled",
                "subject", "member:" + memberId, "time", "2026-09-20T10:05:00Z", "lhcorrelationid", "REQ-" + redemptionId,
                "lhhop", 0, "data", Map.of("redemptionId", redemptionId, "reason", "CUSTOMER_REQUEST", "refund", refund,
                        "pointsCost", cost)));
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

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
