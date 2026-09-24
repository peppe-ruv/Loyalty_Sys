package io.loyaltyhub.member.messaging;

import tools.jackson.databind.JsonNode;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes.Action;
import io.loyaltyhub.common.event.LhSource;
import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.member.application.ReferralService;
import io.loyaltyhub.member.infra.MemberStatsRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

/**
 * Aggiorna {@code member_stats} da ogni azione su {@code lh.actions.v1} (docs/servizi/member-service.md §5).
 * Le azioni interne ({@code source=…:internal}) non aggiornano l'ultima attività né i conteggi acquisti.
 * Nella stessa transazione verifica la qualifica del referral (docs §4: "tutte → member_stats; verifica qualifica").
 */
@Component
public class MemberStatsHandler implements EventHandler {

    private static final Set<String> ACTION_TYPES = Set.of(
            Action.PURCHASE_COMPLETED, Action.PURCHASE_RETURNED, Action.EBILL_ACTIVATED,
            Action.DIRECTDEBIT_ACTIVATED, Action.SELFREADING_SUBMITTED, Action.APP_LOGIN_DAILY,
            Action.SURVEY_COMPLETED, Action.QUIZ_COMPLETED, Action.REVIEW_SUBMITTED, Action.NEWSLETTER_SUBSCRIBED,
            Action.MEMBER_REGISTERED, Action.MEMBER_PROFILE_COMPLETED, Action.MEMBER_BIRTHDAY, Action.TIER_UPGRADED,
            Action.INSTANTWIN_WON, Action.ACHIEVEMENT_COMPLETED, Action.BADGE_AWARDED, Action.REFERRAL_COMPLETED,
            Action.REWARD_REDEEMED);

    private final MemberStatsRepository stats;
    private final ReferralService referrals;

    public MemberStatsHandler(MemberStatsRepository stats, ReferralService referrals) {
        this.stats = stats;
        this.referrals = referrals;
    }

    @Override
    public Set<String> handledTypes() {
        return ACTION_TYPES;
    }

    @Override
    public void handle(LhEvent<JsonNode> event) {
        String memberId = event.memberId();
        if (memberId == null) {
            return; // subject non riconducibile a un membro
        }
        boolean internal = LhSource.INTERNAL.equals(event.source());
        String shortType = shortType(event.type());

        Instant lastActivity = internal ? null : event.time();
        long purchasesDelta = 0;
        BigDecimal amount = BigDecimal.ZERO;
        if (!internal && Action.PURCHASE_COMPLETED.equals(event.type())) {
            purchasesDelta = 1;
            JsonNode data = event.data();
            if (data != null && data.has("amount")) {
                amount = BigDecimal.valueOf(data.path("amount").asDouble(0.0));
            }
        }
        stats.recordAction(memberId, shortType, lastActivity, purchasesDelta, amount);
        referrals.onAction(event);
    }

    private static String shortType(String type) {
        return type.startsWith(Action.PREFIX) ? type.substring(Action.PREFIX.length()) : type;
    }
}
