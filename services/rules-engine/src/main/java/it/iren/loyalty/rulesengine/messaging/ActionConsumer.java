package it.iren.loyalty.rulesengine.messaging;

import it.iren.loyalty.common.event.CanonicalEvents;
import it.iren.loyalty.common.event.EventTypes;
import it.iren.loyalty.common.event.RewardingAction;
import it.iren.loyalty.rulesengine.client.LedgerClient;
import it.iren.loyalty.rulesengine.client.RewardClient;
import it.iren.loyalty.rulesengine.client.SegmentClient;
import it.iren.loyalty.rulesengine.client.TierClient;
import it.iren.loyalty.rulesengine.domain.RuleEvaluator;
import it.iren.loyalty.rulesengine.domain.RuleSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Consuma le azioni premianti (esterne e interne, stesso topic: D08) e accredita tramite il ledger.
 * Un'azione senza regola attiva è conservata e segnalata, non scartata (RF-03): il ledger non riceve nulla e
 * l'evento resta nel topic per una rielaborazione con offset reset o replay.
 */
@Component
public class ActionConsumer {
    private static final Logger log = LoggerFactory.getLogger(ActionConsumer.class);
    private final RuleSource rules;
    private final RuleEvaluator evaluator = new RuleEvaluator();
    private final LedgerClient ledger;
    private final TierClient tiers;
    private final SegmentClient segments;
    private final RewardClient rewards;

    public ActionConsumer(RuleSource rules, LedgerClient ledger, TierClient tiers, SegmentClient segments, RewardClient rewards) {
        this.rules = rules; this.ledger = ledger; this.tiers = tiers; this.segments = segments; this.rewards = rewards;
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
        List<RuleEvaluator.Posting> postings = evaluator.evaluate(action, rules.publishedRulesFor(action.actionType()),
                new RuleEvaluator.Context(tiers.currentTier(memberId), 0, segments.segmentsOf(memberId), java.util.Map.of()));
        if (postings.isEmpty()) {
            log.info("no active rule for actionType={} key={} (kept for replay)", action.actionType(), action.idempotencyKey());
            return;
        }
        ledger.post(memberId, action.idempotencyKey(), postings.stream().filter(p -> p.amount() != 0).toList());
        // premi automatici (RF-76): idempotenti per azione+regola
        for (var p : postings) if (p.autoRewardId() != null) rewards.grant(memberId, p.autoRewardId(), action.idempotencyKey() + ":" + p.ruleId());
    }
}
