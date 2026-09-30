package io.loyaltyhub.wallet.messaging;

import tools.jackson.databind.JsonNode;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes.Fact;
import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.wallet.application.MemberSubjectProjection;
import io.loyaltyhub.wallet.application.WalletService;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Crea i due wallet + livello BASE al fatto {@code member.registered}, riflette {@code member.status.changed}
 * (docs/servizi/wallet-service.md §4) e proietta il legame {@code subjectRef → membro} di {@code member.registered} e
 * {@code member.updated} nella stessa transazione (F2-SEC-09, ADR-048, Q-550). Le richieste premio sono in
 * {@link RedemptionHandler}.
 */
@Component
public class MemberLifecycleHandler implements EventHandler {

    private final WalletService wallet;
    private final MemberSubjectProjection subjects;

    public MemberLifecycleHandler(WalletService wallet, MemberSubjectProjection subjects) {
        this.wallet = wallet;
        this.subjects = subjects;
    }

    @Override
    public Set<String> handledTypes() {
        return Set.of(Fact.MEMBER_REGISTERED, Fact.MEMBER_UPDATED, Fact.MEMBER_STATUS_CHANGED);
    }

    @Override
    public void handle(LhEvent<JsonNode> event) {
        String memberId = event.memberId();
        if (memberId == null) {
            return;
        }
        switch (event.type()) {
            case Fact.MEMBER_REGISTERED -> wallet.createWalletsForMember(memberId);
            case Fact.MEMBER_STATUS_CHANGED -> {
                String status = event.data() != null && event.data().hasNonNull("newStatus")
                        ? event.data().get("newStatus").asString() : "ACTIVE";
                wallet.updateMemberStatus(memberId, status);
            }
            default -> {
                // member.updated: nessun effetto sul wallet, solo il legame (sotto)
            }
        }
        subjects.apply(memberId, event);
    }
}
