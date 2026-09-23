package io.loyaltyhub.wallet.application;

import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.wallet.domain.EditionCloseRule;
import io.loyaltyhub.wallet.domain.MemberTier;
import io.loyaltyhub.wallet.domain.Tier;
import io.loyaltyhub.wallet.infra.MemberTierRepository;
import io.loyaltyhub.wallet.infra.TierHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class EditionCloseBatchServiceTest {

    @Mock private io.loyaltyhub.wallet.infra.EditionRepository editions;
    @Mock private MemberTierRepository memberTiers;
    @Mock private TierHistoryRepository tierHistory;
    @Mock private LhEventFactory events;
    @Mock private OutboxWriter outbox;
    @Mock private io.loyaltyhub.common.audit.AuditPublisher audit;

    private EditionCloseBatchService service;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.systemUTC();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new EditionCloseBatchService(editions, memberTiers, tierHistory, events, outbox, audit, mapper, clock);
    }

    @Test
    void testProcessBatchDryRun() {
        List<MemberTier> batch = List.of(
            new MemberTier("MBR-1", "GOLD", Instant.now(), 650, "SILVER", "ACTIVE")
        );

        List<Tier> scale = List.of(
            new Tier("BASE", "Base", 0, 0, BigDecimal.ONE, List.of(), null, null),
            new Tier("SILVER", "Silver", 1, 1000, BigDecimal.valueOf(1.25), List.of(), null, null),
            new Tier("GOLD", "Gold", 2, 3000, BigDecimal.valueOf(1.5), List.of(), null, null)
        );

        var result = service.processBatch(batch, scale, "ED-2026", true);

        assertThat(result.downgraded()).isEqualTo(1);
        assertThat(result.retained()).isEqualTo(0);
        assertThat(result.previewMembers()).hasSize(1);
        assertThat(result.previewMembers().get(0).newTier()).isEqualTo("SILVER");

        verifyNoInteractions(memberTiers, tierHistory, outbox);
    }
}
