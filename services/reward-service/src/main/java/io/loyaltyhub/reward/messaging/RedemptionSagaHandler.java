package io.loyaltyhub.reward.messaging;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes.Fact;
import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.reward.application.RedemptionService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Set;

/** Esiti del wallet nella saga di richiesta premio (docs/servizi/reward-service.md §4–5). */
@Component
public class RedemptionSagaHandler implements EventHandler {

    private final RedemptionService redemptions;

    public RedemptionSagaHandler(RedemptionService redemptions) {
        this.redemptions = redemptions;
    }

    @Override
    public Set<String> handledTypes() {
        return Set.of(Fact.WALLET_POINTS_SPENT, Fact.WALLET_SPEND_REJECTED);
    }

    @Override
    public void handle(LhEvent<JsonNode> event) {
        if (Fact.WALLET_POINTS_SPENT.equals(event.type())) {
            redemptions.onPointsSpent(event);
        } else {
            redemptions.onSpendRejected(event);
        }
    }
}
