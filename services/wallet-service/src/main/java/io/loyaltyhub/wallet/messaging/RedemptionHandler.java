package io.loyaltyhub.wallet.messaging;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes.Fact;
import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.wallet.application.RedemptionPayments;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Set;

/**
 * Saga di richiesta premio, lato wallet (docs/servizi/wallet-service.md §4): spesa alla richiesta, rimborso
 * all'annullo con {@code refund=true}.
 */
@Component
public class RedemptionHandler implements EventHandler {

    private final RedemptionPayments payments;

    public RedemptionHandler(RedemptionPayments payments) {
        this.payments = payments;
    }

    @Override
    public Set<String> handledTypes() {
        return Set.of(Fact.REWARD_REDEMPTION_REQUESTED, Fact.REWARD_REDEMPTION_CANCELLED);
    }

    @Override
    public void handle(LhEvent<JsonNode> event) {
        if (Fact.REWARD_REDEMPTION_REQUESTED.equals(event.type())) {
            payments.spend(event);
        } else {
            payments.refund(event);
        }
    }
}
