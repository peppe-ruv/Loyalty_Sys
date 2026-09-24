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
import io.loyaltyhub.wallet.messaging.PointsGrantHandler;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EmbeddedKafka(partitions = 1, topics = {"lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestbookWalletGrantIT {

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
    private PointsGrantHandler grantHandler;

    @Autowired
    private PointsLotRepository lots;

    @Autowired
    private WalletRepository wallets;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private LhEventFactory events;

    @Test
    @DisplayName("[TB-WAL-GRT-001] PTS base 100, no mult -> ACTIVE, 100 PTS")
    void pointsGrantedActive() {
        String memberId = "MBR-000004";
        String effectId = UUID.randomUUID().toString();

        tools.jackson.databind.node.ObjectNode data = mapper.createObjectNode();
        data.put("currency", "PTS");
        data.put("baseAmount", 100);
        data.put("campaignMultiplier", 1.0);
        data.put("amount", 100);
        data.put("tierMultiplierApplies", false);
        data.put("pendingDays", 0);
        data.put("description", "Test GRT-001");

        LhEvent<JsonNode> evt = events.newRoot("io.loyaltyhub.effect.points.grant", "member:" + memberId, data);
        evt = evt.withCorrelation(effectId, null, 0); // use correlation as effect ID workaround for test

        grantHandler.handle(evt);

        List<PointsLot> openLots = lots.findOpenByMember(memberId);
        PointsLot newLot = openLots.stream()
                .filter(l -> l.ledgerEntryId() != null)
                .max(java.util.Comparator.comparing(PointsLot::earnedAt))
                .orElseThrow();

        assertThat(newLot.status()).isEqualTo("ACTIVE");
        assertThat(newLot.amount()).isEqualTo(100);
    }

    @Test
    @DisplayName("[TB-WAL-GRT-002] PTS base 100, pending -> PENDING, 100 PTS")
    void pointsGrantedPending() {
        String memberId = "MBR-000004";
        String effectId = UUID.randomUUID().toString();

        tools.jackson.databind.node.ObjectNode data = mapper.createObjectNode();
        data.put("currency", "PTS");
        data.put("baseAmount", 100);
        data.put("campaignMultiplier", 1.0);
        data.put("amount", 100);
        data.put("tierMultiplierApplies", false);
        data.put("pendingDays", 5);
        data.put("description", "Test GRT-002");

        LhEvent<JsonNode> evt = events.newRoot("io.loyaltyhub.effect.points.grant", "member:" + memberId, data);
        evt = evt.withCorrelation(effectId, null, 0);

        grantHandler.handle(evt);

        List<PointsLot> pendingLots = lots.findOpenByMember(memberId);
        PointsLot newLot = pendingLots.stream()
                .filter(l -> l.ledgerEntryId() != null && "PENDING".equals(l.status()))
                .max(java.util.Comparator.comparing(PointsLot::earnedAt))
                .orElseThrow();

        assertThat(newLot.status()).isEqualTo("PENDING");
        assertThat(newLot.amount()).isEqualTo(100);
    }
}
