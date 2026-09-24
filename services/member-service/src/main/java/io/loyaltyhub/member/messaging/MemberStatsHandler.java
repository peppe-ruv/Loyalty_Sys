package io.loyaltyhub.member.messaging;

import tools.jackson.databind.JsonNode;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes.Action;
import io.loyaltyhub.common.event.LhSource;
import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.member.application.MemberService;
import io.loyaltyhub.member.application.ReferralService;
import io.loyaltyhub.member.application.SegmentChangeTracker;
import io.loyaltyhub.member.infra.MemberStatsRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

/**
 * Aggiorna {@code member_stats} da ogni azione su {@code lh.actions.v1} (docs/servizi/member-service.md §5).
 * Le azioni interne ({@code source=…:internal}) non aggiornano l'ultima attività né i conteggi acquisti.
 * Nella stessa transazione verifica la qualifica del referral (docs §4: "tutte → member_stats; verifica qualifica"),
 * applica le etichette attribuite dall'azione (SPEC-GAP Q-80) e segnala la variazione al ricalcolo dei segmenti.
 */
@Component
public class MemberStatsHandler implements EventHandler {

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");

    /** Tutta la famiglia delle azioni, compresi i tipi custom (F-ING-06, M6.7): contano nei criteri dei segmenti. */
    private static final Set<String> ACTION_TYPES = Set.of(Action.PREFIX + "*");

    private final MemberStatsRepository stats;
    private final ReferralService referrals;
    private final MemberService members;
    private final SegmentChangeTracker segmentChanges;
    private final Clock clock;

    public MemberStatsHandler(MemberStatsRepository stats, ReferralService referrals, MemberService members,
                              SegmentChangeTracker segmentChanges, Clock clock) {
        this.stats = stats;
        this.referrals = referrals;
        this.members = members;
        this.segmentChanges = segmentChanges;
        this.clock = clock;
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
        Instant time = event.time() != null ? event.time() : clock.instant();

        Instant lastActivity = internal ? null : time;
        long purchasesDelta = 0;
        BigDecimal amount = BigDecimal.ZERO;
        if (!internal && Action.PURCHASE_COMPLETED.equals(event.type())) {
            purchasesDelta = 1;
            JsonNode data = event.data();
            if (data != null && data.has("amount")) {
                amount = BigDecimal.valueOf(data.path("amount").asDouble(0.0));
            }
        }
        stats.recordAction(memberId, shortType, time, lastActivity, purchasesDelta, amount, LocalDate.now(clock.withZone(ROME)));
        referrals.onAction(event);
        if (!internal) {
            members.applyActionLabels(event, shortType);
        }
        segmentChanges.markChanged();
    }

    static String shortType(String type) {
        return type.startsWith(Action.PREFIX) ? type.substring(Action.PREFIX.length()) : type;
    }
}
