package io.loyaltyhub.reward.messaging;

import tools.jackson.databind.JsonNode;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.common.privacy.PersonalData;
import io.loyaltyhub.reward.infra.MemberSnapshotRepository;
import io.loyaltyhub.reward.infra.RedemptionErasureRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Snapshot del membro per la visibilità dei premi (docs/servizi/reward-service.md §4): stato e nome dai fatti di
 * member, livello da {@code tier.upgraded}/{@code tier.downgraded}, segmenti da {@code member.segment.entered/left} (M6.6).
 */
@Component
public class MemberSnapshotHandler implements EventHandler {

    private final MemberSnapshotRepository members;
    private final RedemptionErasureRepository redemptions;

    public MemberSnapshotHandler(MemberSnapshotRepository members, RedemptionErasureRepository redemptions) {
        this.members = members;
        this.redemptions = redemptions;
    }

    @Override
    public Set<String> handledTypes() {
        return Set.of(LhEventTypes.Fact.MEMBER_REGISTERED, LhEventTypes.Fact.MEMBER_UPDATED,
                LhEventTypes.Fact.MEMBER_STATUS_CHANGED, LhEventTypes.Fact.TIER_UPGRADED, LhEventTypes.Fact.TIER_DOWNGRADED,
                LhEventTypes.Fact.MEMBER_SEGMENT_ENTERED, LhEventTypes.Fact.MEMBER_SEGMENT_LEFT);
    }

    @Override
    public void handle(LhEvent<JsonNode> event) {
        String s = event.subject();
        if (s == null || !s.startsWith("member:") || event.data() == null) {
            return;
        }
        String memberId = s.substring("member:".length());
        JsonNode d = event.data();
        switch (event.type()) {
            case LhEventTypes.Fact.MEMBER_STATUS_CHANGED -> {
                if (d.hasNonNull("newStatus")) {
                    members.updateStatus(memberId, d.get("newStatus").asString());
                }
            }
            case LhEventTypes.Fact.TIER_UPGRADED, LhEventTypes.Fact.TIER_DOWNGRADED -> {
                if (d.hasNonNull("newTier")) {
                    members.updateTier(memberId, d.get("newTier").asString());
                }
            }
            // Visibilità per segmento (F-RWD-04 P1, M6.6): i segmenti arrivano solo dai fatti di member.
            case LhEventTypes.Fact.MEMBER_SEGMENT_ENTERED -> {
                if (d.hasNonNull("segmentCode")) {
                    members.addSegment(memberId, d.get("segmentCode").asString());
                }
            }
            case LhEventTypes.Fact.MEMBER_SEGMENT_LEFT -> {
                if (d.hasNonNull("segmentCode")) {
                    members.removeSegment(memberId, d.get("segmentCode").asString());
                }
            }
            default -> members.upsertProfile(memberId, d.path("status").asString("ACTIVE"),
                    d.hasNonNull("firstName") ? d.get("firstName").asString() : null,
                    d.hasNonNull("lastName") ? d.get("lastName").asString() : null);
        }
        // Anonimizzazione (F-MBR-05, M7.5): i nomi ancora nello snapshot servono a ripulire le note, poi si cancellano.
        if (PersonalData.isAnonymization(event)) {
            List<String> tokens = members.find(memberId)
                    .map(m -> PersonalData.nameTokens(m.firstName(), m.lastName()))
                    .orElse(List.of());
            redemptions.erase(memberId, tokens);
            members.erasePersonal(memberId);
        }
    }
}
