package io.loyaltyhub.wallet.application;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.wallet.domain.EditionCloseRule;
import io.loyaltyhub.wallet.domain.MemberTier;
import io.loyaltyhub.wallet.domain.Tier;
import io.loyaltyhub.wallet.domain.TierHistory;
import io.loyaltyhub.wallet.infra.MemberTierRepository;
import io.loyaltyhub.wallet.infra.TierHistoryRepository;
import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.wallet.domain.Edition;
import io.loyaltyhub.wallet.infra.EditionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class EditionCloseBatchService {

    private final EditionRepository editions;
    private final MemberTierRepository memberTiers;
    private final TierHistoryRepository tierHistory;
    private final LhEventFactory events;
    private final OutboxWriter outbox;
    private final AuditPublisher audit;
    private final ObjectMapper mapper;
    private final Clock clock;

    public EditionCloseBatchService(EditionRepository editions,
                                    MemberTierRepository memberTiers,
                                    TierHistoryRepository tierHistory,
                                    LhEventFactory events, OutboxWriter outbox,
                                    AuditPublisher audit, ObjectMapper mapper, Clock clock) {
        this.editions = editions;
        this.memberTiers = memberTiers;
        this.tierHistory = tierHistory;
        this.events = events;
        this.outbox = outbox;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
    }

    public record BatchResult(int retained, int downgraded, List<EditionService.ClosePreviewMember> previewMembers) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchResult processBatch(List<MemberTier> batch, List<Tier> scale, String editionCode, boolean dryRun) {
        int retained = 0;
        int downgraded = 0;
        List<EditionService.ClosePreviewMember> membersPreview = new ArrayList<>();

        for (MemberTier mt : batch) {
            EditionCloseRule.Result next = EditionCloseRule.computeNext(mt.tierCode(), mt.periodSts(), scale);

            membersPreview.add(new EditionService.ClosePreviewMember(mt.memberId(), mt.tierCode(), mt.periodSts(), next.earnedTier(), next.newTier(), next.outcome()));

            if (next.outcome() == EditionCloseRule.Outcome.RETAINED) {
                retained++;
            } else {
                downgraded++;
            }

            if (!dryRun) {
                if (next.outcome() == EditionCloseRule.Outcome.RETAINED) {
                    memberTiers.resetPeriodSts(mt.memberId());
                } else {
                    memberTiers.updateTierAndResetSts(mt.memberId(), next.newTier(), mt.tierCode());
                }

                String kind = next.outcome() == EditionCloseRule.Outcome.RETAINED ? TierHistory.RETAIN : TierHistory.DOWNGRADE;
                tierHistory.insert(new TierHistory(Ulid.next(clock), mt.memberId(), mt.tierCode(), next.newTier(), kind, editionCode, clock.instant()));

                ObjectNode data = mapper.createObjectNode();
                if (next.outcome() == EditionCloseRule.Outcome.DOWNGRADED) {
                    data.put("previousTier", mt.tierCode());
                    data.put("newTier", next.newTier());
                    data.put("editionCode", editionCode);
                    outbox.write(events.newRoot(LhEventTypes.Fact.TIER_DOWNGRADED, "member:" + mt.memberId(), data));
                } else {
                    data.put("tier", next.newTier());
                    data.put("editionCode", editionCode);
                    outbox.write(events.newRoot(LhEventTypes.Fact.TIER_RETAINED, "member:" + mt.memberId(), data));
                }
            }
        }
        return new BatchResult(retained, downgraded, membersPreview);
    }

    @Transactional
    public void finalizeClose(String code, int totalRetained, int totalDowngraded) {
        editions.updateStatus(code, Edition.CLOSED);

        Edition nextEdition = editions.findAll().stream()
                .filter(e -> Edition.PLANNED.equals(e.status()))
                .min(Comparator.comparing(Edition::startDate))
                .orElse(null);

        if (nextEdition != null) {
            editions.updateStatus(nextEdition.code(), Edition.ACTIVE);
        }

        ObjectNode data = mapper.createObjectNode();
        data.put("editionCode", code);
        data.put("retained", totalRetained);
        data.put("downgraded", totalDowngraded);
        outbox.write(events.newRoot(LhEventTypes.Fact.EDITION_CLOSED, "edition:" + code, data));

        audit.record("edition", code, AuditEntry.Action.TRANSITION, "Chiusa edizione " + code,
                Map.of("status", Edition.ACTIVE), Map.of("status", Edition.CLOSED));
    }
}
