package io.loyaltyhub.wallet.messaging;

import tools.jackson.databind.JsonNode;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes.Fact;
import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.wallet.application.WalletService;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Crea i due wallet + livello BASE al fatto {@code member.registered}, riflette {@code member.status.changed}
 * (docs/servizi/wallet-service.md §4). Le richieste premio (saga di spesa) arrivano con M4.
 */
@Component
public class MemberLifecycleHandler implements EventHandler {

    private final WalletService wallet;

    public MemberLifecycleHandler(WalletService wallet) {
        this.wallet = wallet;
    }

    @Override
    public Set<String> handledTypes() {
        return Set.of(Fact.MEMBER_REGISTERED, Fact.MEMBER_STATUS_CHANGED);
    }

    @Override
    public void handle(LhEvent<JsonNode> event) {
        String memberId = event.memberId();
        if (memberId == null) {
            return;
        }
        if (Fact.MEMBER_REGISTERED.equals(event.type())) {
            wallet.createWalletsForMember(memberId);
        } else {
            String status = event.data() != null && event.data().hasNonNull("newStatus")
                    ? event.data().get("newStatus").asString() : "ACTIVE";
            wallet.updateMemberStatus(memberId, status);
        }
    }
}
