package it.iren.loyalty.decisionservice.messaging;

import it.iren.loyalty.common.event.CanonicalEvents;
import it.iren.loyalty.common.event.EventTypes;
import it.iren.loyalty.common.event.RewardingAction;
import it.iren.loyalty.decisionservice.app.DecisionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Consuma le azioni premianti dal topic canonico e avvia il ciclo decisionale (RF-127). Le azioni "di servizio"
 * emesse dal decision-service stesso (esposizioni, completamenti, variazioni di rischio) non sono ri-decise per evitare
 * cicli; gli storni non generano decisioni (li gestisce il ledger). Con {@code decisions.enabled=false} il servizio
 * resta passivo e il rules-engine continua ad applicare gli effetti da solo (retro-compatibilità, RF-136).
 */
@Component
public class ActionDecisionConsumer {
    private static final Logger log = LoggerFactory.getLogger(ActionDecisionConsumer.class);
    private static final Set<String> SKIP = Set.of(EventTypes.ACTION_EXPERIMENT_EXPOSED, EventTypes.ACTION_CAMPAIGN_COMPLETED, EventTypes.ACTION_CAMPAIGN_ENTERED,
            EventTypes.ACTION_CHURN_RISK_CHANGED, EventTypes.ACTION_OFFER_PRESENTED, EventTypes.ACTION_MESSAGE_SENT, EventTypes.ACTION_FEEDBACK_REQUESTED);

    private final DecisionService decisions;
    private final boolean enabled;

    public ActionDecisionConsumer(DecisionService decisions, @Value("${decisions.enabled:true}") boolean enabled) { this.decisions = decisions; this.enabled = enabled; }

    @KafkaListener(topics = EventTypes.TOPIC_ACTIONS, groupId = "decision-service", concurrency = "${decisions.consumer.concurrency:6}")
    public void onAction(byte[] payload) {
        if (!enabled) return;
        var event = CanonicalEvents.deserialize(payload);
        if (!EventTypes.ACTION_V1.equals(event.getType())) return;
        var action = CanonicalEvents.data(event, RewardingAction.class);
        if (action.isReversal() || SKIP.contains(action.actionType())) return;
        String memberId = CanonicalEvents.memberId(event);
        try {
            var d = decisions.decideForAction(memberId, action, event.getId(), CanonicalEvents.correlationId(event));
            log.info("decision {} for member {} on {}: {} (rejected {})", d.decisionId(), memberId, action.actionType(), d.primaryAction(), d.rejected().size());
        } catch (Exception e) {
            log.error("decision failed for member {} action {}: {}", memberId, action.idempotencyKey(), e.toString());
            throw e;
        }
    }
}
