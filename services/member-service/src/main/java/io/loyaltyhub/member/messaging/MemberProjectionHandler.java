package io.loyaltyhub.member.messaging;

import tools.jackson.databind.JsonNode;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes.Fact;
import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.member.infra.MemberProjectionRepository;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Riflette in {@code member_projection} i fatti del wallet/motore (docs/servizi/member-service.md §4, §5):
 * {@code wallet.points.earned} → {@code balance_pts = balanceAfter}; {@code tier.upgraded} → {@code tier_code}.
 * I fatti prodotti da member stesso ({@code member.*}) non hanno handler qui: vengono ignorati (nessun loop).
 */
@Component
public class MemberProjectionHandler implements EventHandler {

    private final MemberProjectionRepository projections;

    public MemberProjectionHandler(MemberProjectionRepository projections) {
        this.projections = projections;
    }

    @Override
    public Set<String> handledTypes() {
        return Set.of(Fact.WALLET_POINTS_EARNED, Fact.TIER_UPGRADED);
    }

    @Override
    public void handle(LhEvent<JsonNode> event) {
        String memberId = event.memberId();
        if (memberId == null) {
            return;
        }
        JsonNode data = event.data();
        switch (event.type()) {
            case Fact.WALLET_POINTS_EARNED -> {
                long balanceAfter = data != null ? data.path("balanceAfter").asLong(0) : 0;
                long earned = data != null ? data.path("points").asLong(0) : 0;
                projections.applyPointsBalance(memberId, balanceAfter, earned);
            }
            case Fact.TIER_UPGRADED -> {
                String newTier = data != null ? data.path("newTier").asString(null) : null;
                if (newTier != null) {
                    projections.applyTier(memberId, newTier);
                }
            }
            default -> {
                // type non gestito: ignorato
            }
        }
    }
}
