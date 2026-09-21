package io.loyaltyhub.wallet.messaging;

import tools.jackson.databind.JsonNode;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes.Effect;
import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.wallet.application.WalletService;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Applica gli effetti {@code points.grant} da {@code lh.effects.v1} (docs/servizi/wallet-service.md §4). */
@Component
public class PointsGrantHandler implements EventHandler {

    private final WalletService wallet;

    public PointsGrantHandler(WalletService wallet) {
        this.wallet = wallet;
    }

    @Override
    public Set<String> handledTypes() {
        return Set.of(Effect.POINTS_GRANT);
    }

    @Override
    public void handle(LhEvent<JsonNode> event) {
        wallet.applyGrant(event);
    }
}
