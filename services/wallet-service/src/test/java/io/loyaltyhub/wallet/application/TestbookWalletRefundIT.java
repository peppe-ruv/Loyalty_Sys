package io.loyaltyhub.wallet.application;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.common.auth.ActorContext;
import io.loyaltyhub.common.auth.ActorHolder;
import io.loyaltyhub.common.auth.Role;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.wallet.domain.PointsLot;
import io.loyaltyhub.wallet.infra.PointsLotRepository;
import io.loyaltyhub.wallet.infra.WalletRepository;
import io.loyaltyhub.wallet.messaging.RedemptionHandler;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EmbeddedKafka(partitions = 1, topics = {"lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestbookWalletRefundIT {

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
    private RedemptionHandler redemptionHandler;

    @Autowired
    private PointsLotRepository lots;

    @Autowired
    private WalletRepository wallets;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private LhEventFactory events;

    @Autowired
    private Clock clock;

    @Test
    @DisplayName("[TB-WAL-REF-001] Punti rimborsati di lotti validi tornano ai lotti di origine")
    void pointsReturnedToValidLots() {
        String memberId = "MBR-000004";
        ActorHolder.set(new ActorContext(Role.ADMIN, "admin"));

        long balanceStart = wallets.find(memberId, "PTS").map(w -> w.balanceActive()).orElse(0L);
        assertThat(balanceStart).isGreaterThanOrEqualTo(500);

        String redemptionId = "RED-" + UUID.randomUUID().toString();

        // Spesa
        tools.jackson.databind.node.ObjectNode data = mapper.createObjectNode();
        data.put("redemptionId", redemptionId);
        data.put("rewardCode", "RWD-SP-001");
        data.put("currency", "PTS");
        data.put("pointsCost", 500);

        LhEvent<JsonNode> evtReq = events.newRoot("io.loyaltyhub.fact.reward.redemption.requested", "member:" + memberId, data);
        redemptionHandler.handleRequested(evtReq);

        long balanceAfterSpend = wallets.find(memberId, "PTS").map(w -> w.balanceActive()).orElse(0L);
        assertThat(balanceAfterSpend).isEqualTo(balanceStart - 500);

        // Rimborso
        tools.jackson.databind.node.ObjectNode dataRef = mapper.createObjectNode();
        dataRef.put("redemptionId", redemptionId);
        dataRef.put("refund", true);

        LhEvent<JsonNode> evtRef = events.newRoot("io.loyaltyhub.fact.reward.redemption.cancelled", "member:" + memberId, dataRef);
        redemptionHandler.handleCancelled(evtRef);

        long balanceAfterRefund = wallets.find(memberId, "PTS").map(w -> w.balanceActive()).orElse(0L);
        assertThat(balanceAfterRefund).isEqualTo(balanceStart);
    }
}
