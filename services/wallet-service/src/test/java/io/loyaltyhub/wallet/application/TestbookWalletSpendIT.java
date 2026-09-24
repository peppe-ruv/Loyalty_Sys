package io.loyaltyhub.wallet.application;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.common.auth.ActorContext;
import io.loyaltyhub.common.auth.ActorHolder;
import io.loyaltyhub.common.auth.Role;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.wallet.domain.PointsLot;
import io.loyaltyhub.wallet.infra.LedgerRepository;
import io.loyaltyhub.wallet.infra.PointsLotRepository;
import io.loyaltyhub.wallet.infra.WalletRepository;
import io.loyaltyhub.wallet.messaging.RedemptionHandler;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EmbeddedKafka(partitions = 1, topics = {"lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestbookWalletSpendIT {

    private static final EmbeddedPostgres PG;

    static {
        try {
            PG = EmbeddedPostgres.builder().start();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    @AfterAll
    void cleanup() throws Exception {
        PG.close();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "postgres");
    }

    @Autowired
    private WalletService walletService;

    @Autowired
    private PointsLotRepository lots;

    @Autowired
    private RedemptionHandler redemptionHandler;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private LhEventFactory events;

    @Autowired
    private WalletRepository wallets;

    @Test
    @DisplayName("[TB-WAL-SPD-001] Consumo FIFO lotti misti - docs F-WAL-04")
    void spendFifoOrder() {
        String memberId = "MBR-000003";
        ActorHolder.set(new ActorContext(Role.ADMIN, "admin"));

        long balanceStart = wallets.find(memberId, "PTS").map(w -> w.balanceActive()).orElse(0L);

        // Add lots to consume specifically
        String r1 = walletService.adjustBalance(memberId, "PTS", "CREDIT", 500, "GOODWILL", "Lot A").ledgerEntryId();
        String r2 = walletService.adjustBalance(memberId, "PTS", "CREDIT", 800, "GOODWILL", "Lot B").ledgerEntryId();
        String r3 = walletService.adjustBalance(memberId, "PTS", "CREDIT", 900, "GOODWILL", "Lot C").ledgerEntryId();

        List<PointsLot> openLots = lots.findOpenByMember(memberId);
        PointsLot lotA = openLots.stream().filter(l -> r1.equals(l.ledgerEntryId())).findFirst().orElseThrow();
        PointsLot lotB = openLots.stream().filter(l -> r2.equals(l.ledgerEntryId())).findFirst().orElseThrow();
        PointsLot lotC = openLots.stream().filter(l -> r3.equals(l.ledgerEntryId())).findFirst().orElseThrow();

        String redemptionId = "RED-" + UUID.randomUUID().toString();

        // Simula evento da kafka
        tools.jackson.databind.node.ObjectNode data = mapper.createObjectNode();
        data.put("redemptionId", redemptionId);
        data.put("rewardCode", "RWD-SP-001");
        data.put("currency", "PTS");
        data.put("pointsCost", balanceStart + 1500); // spende tutto quello che c'era + 1500

        LhEvent<JsonNode> evt = events.newRoot("io.loyaltyhub.fact.reward.redemption.requested", "member:" + memberId, data);

        redemptionHandler.handleRequested(evt);

        // Verify consumption
        List<PointsLotRepository.Consumption> consumptions = lots.consumptionsByLotIds(List.of(lotA.id(), lotB.id(), lotC.id()));

        long consA = consumptions.stream().filter(c -> c.lotId().equals(lotA.id())).mapToLong(c -> c.amount()).sum();
        long consB = consumptions.stream().filter(c -> c.lotId().equals(lotB.id())).mapToLong(c -> c.amount()).sum();
        long consC = consumptions.stream().filter(c -> c.lotId().equals(lotC.id())).mapToLong(c -> c.amount()).sum();

        // Because of FIFO, the existing balanceStart lots were consumed first.
        // Then Lot A (500), Lot B (800) and Lot C (200) should be consumed to make up the 1500.
        // If sorting happened exactly in creation order:
        assertThat(consA).isEqualTo(500);
        assertThat(consB).isEqualTo(800);
        assertThat(consC).isEqualTo(200);
    }
}
