package io.loyaltyhub.member.messaging;

import tools.jackson.databind.JsonNode;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes.Fact;
import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.member.application.SegmentChangeTracker;
import io.loyaltyhub.member.infra.MemberProjectionRepository;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Riflette in {@code member_projection} i fatti del wallet/motore (docs/servizi/member-service.md §4, §5):
 * {@code wallet.points.*} → saldo ({@code balanceAfter}: PTS in {@code balance_pts}, STS in {@code period_sts}),
 * {@code wallet.points.earned} PTS → anche {@code lifetime_earned_pts}; {@code tier.*} → {@code tier_code}.
 * Il saldo e il livello entrano nei criteri dei segmenti ({@code balance.PTS}, {@code tier}): ogni variazione è
 * segnalata al ricalcolo. I fatti prodotti da member stesso ({@code member.*}) non hanno handler qui (nessun loop).
 */
@Component
public class MemberProjectionHandler implements EventHandler {

    private static final Set<String> BALANCE_FACTS = Set.of(
            Fact.WALLET_POINTS_EARNED, Fact.WALLET_POINTS_SPENT, Fact.WALLET_POINTS_EXPIRED,
            Fact.WALLET_POINTS_ADJUSTED, Fact.WALLET_POINTS_REFUNDED, Fact.WALLET_POINTS_RELEASED);

    private final MemberProjectionRepository projections;
    private final SegmentChangeTracker segmentChanges;

    public MemberProjectionHandler(MemberProjectionRepository projections, SegmentChangeTracker segmentChanges) {
        this.projections = projections;
        this.segmentChanges = segmentChanges;
    }

    @Override
    public Set<String> handledTypes() {
        return Set.of(Fact.WALLET_POINTS_EARNED, Fact.WALLET_POINTS_SPENT, Fact.WALLET_POINTS_EXPIRED,
                Fact.WALLET_POINTS_ADJUSTED, Fact.WALLET_POINTS_REFUNDED, Fact.WALLET_POINTS_RELEASED,
                Fact.TIER_UPGRADED, Fact.TIER_DOWNGRADED, Fact.TIER_RETAINED);
    }

    @Override
    public void handle(LhEvent<JsonNode> event) {
        String memberId = event.memberId();
        JsonNode data = event.data();
        if (memberId == null || data == null) {
            return;
        }
        if (BALANCE_FACTS.contains(event.type())) {
            if (!data.hasNonNull("balanceAfter")) {
                return;
            }
            long balanceAfter = data.path("balanceAfter").asLong(0);
            // Valuta assente (es. refunded) = PTS: è l'unica spendibile (docs/03 §4.1).
            String currency = data.path("currency").asString("PTS");
            if ("STS".equals(currency)) {
                projections.applyStatusBalance(memberId, balanceAfter);
            } else {
                long earned = 0;
                if (Fact.WALLET_POINTS_EARNED.equals(event.type())) {
                    // Il contratto (EVT-FACT-20) porta `amount`; `points` resta come ripiego per i fatti già pubblicati.
                    earned = data.hasNonNull("amount") ? data.path("amount").asLong(0) : data.path("points").asLong(0);
                }
                projections.applyPointsBalance(memberId, balanceAfter, earned);
            }
            segmentChanges.markChanged();
            return;
        }
        String tier = Fact.TIER_RETAINED.equals(event.type())
                ? data.path("tier").asString(data.path("newTier").asString(null))
                : data.path("newTier").asString(null);
        if (tier != null) {
            projections.applyTier(memberId, tier);
            segmentChanges.markChanged();
        }
    }
}
