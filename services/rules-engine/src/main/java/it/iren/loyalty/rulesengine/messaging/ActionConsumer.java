package it.iren.loyalty.rulesengine.messaging;

import it.iren.loyalty.common.event.CanonicalEvents;
import it.iren.loyalty.common.event.Currency;
import it.iren.loyalty.common.event.EventTypes;
import it.iren.loyalty.common.event.RewardingAction;
import it.iren.loyalty.rulesengine.campaign.Campaign;
import it.iren.loyalty.rulesengine.campaign.CampaignEvaluator;
import it.iren.loyalty.rulesengine.campaign.CampaignSource;
import it.iren.loyalty.rulesengine.client.*;
import it.iren.loyalty.rulesengine.domain.RuleEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Consuma le azioni premianti (esterne e interne, stesso topic: D08), valuta le campagne (RF-80) e applica gli effetti:
 * unità sul ledger (con scadenza/sospensione calcolate), premi, badge, attributi, tier. Un'azione senza campagna attiva è
 * conservata e segnalata, non scartata (RF-03).
 */
@Component
public class ActionConsumer {
    private static final Logger log = LoggerFactory.getLogger(ActionConsumer.class);
    private final CampaignSource campaigns;
    private final CampaignEvaluator evaluator;
    private final LedgerClient ledger;
    private final TierClient tiers;
    private final SegmentClient segments;
    private final RewardClient rewards;
    private final EngagementClient engagement;

    public ActionConsumer(CampaignSource campaigns, CampaignEvaluator evaluator, LedgerClient ledger, TierClient tiers, SegmentClient segments, RewardClient rewards, EngagementClient engagement) {
        this.campaigns = campaigns; this.evaluator = evaluator; this.ledger = ledger; this.tiers = tiers; this.segments = segments; this.rewards = rewards; this.engagement = engagement;
    }

    @KafkaListener(topics = EventTypes.TOPIC_ACTIONS, concurrency = "${rules.consumer.concurrency:6}")
    public void onAction(byte[] payload) {
        var event = CanonicalEvents.deserialize(payload);
        if (!EventTypes.ACTION_V1.equals(event.getType())) return;
        var action = CanonicalEvents.data(event, RewardingAction.class);
        String memberId = CanonicalEvents.memberId(event);

        if (action.isReversal()) {
            ledger.reverse(action.reversalOf());
            return;
        }
        var member = new CampaignEvaluator.MemberContext(memberId, tiers.currentTier(memberId), segments.segmentsOf(memberId), ledger.wallets(memberId), Map.of(), engagement.badgesOf(memberId), null, null);
        var result = evaluator.evaluate(action, campaigns.publishedCampaignsFor(action.actionType()), member, ledger.usage(memberId, action.actionType()), Instant.now());
        if (result.outcomes().isEmpty()) {
            log.info("no campaign fired for actionType={} key={} skipped={} (kept for replay)", action.actionType(), action.idempotencyKey(), result.skipped());
            return;
        }
        List<RuleEvaluator.Posting> postings = result.outcomes().stream().filter(CampaignEvaluator.Outcome::isUnits)
                .map(o -> new RuleEvaluator.Posting(o.campaignId(), o.version(), Currency.of(o.wallet()), o.signedUnits(), 0, null, o.expiresAt(), o.pendingUntil())).toList();
        if (!postings.isEmpty()) ledger.post(memberId, action.idempotencyKey(), postings);
        for (var o : result.outcomes()) {
            String key = action.idempotencyKey() + ":" + o.campaignId() + ":" + o.ruleId();
            switch (o.type()) {
                case GIVE_REWARD -> rewards.grant(memberId, o.reference(), key);
                case GRANT_BADGE -> engagement.grantBadge(memberId, o.reference(), key);
                case SET_ATTRIBUTE -> engagement.setAttribute(memberId, o.reference(), o.params().get("value"));
                case REMOVE_ATTRIBUTE -> engagement.setAttribute(memberId, o.reference(), null);
                case ASSIGN_TIER -> tiers.assign(memberId, o.reference(), "campaign:" + o.campaignId());
                case EMIT_EVENT -> engagement.emit(memberId, o.reference(), key);
                default -> {}
            }
        }
    }
}
