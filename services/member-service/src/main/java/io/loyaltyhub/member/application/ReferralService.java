package io.loyaltyhub.member.application;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.member.api.ReferralViews.Overview;
import io.loyaltyhub.member.api.ReferralViews.PortalInvitee;
import io.loyaltyhub.member.api.ReferralViews.PortalReferral;
import io.loyaltyhub.member.api.ReferralViews.ReferralLink;
import io.loyaltyhub.member.domain.Member;
import io.loyaltyhub.member.infra.MemberRepository;
import io.loyaltyhub.member.infra.ReferralRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Referral (docs/03 §8, docs/servizi/member-service.md §5, F-REF-01/02). Alla <em>prima</em> azione di tipo
 * {@code loyaltyhub.referral.qualifying-action-type} (seed: {@code purchase.completed}) di un invitato non ancora
 * completato → due fatti {@code referral.completed}: {@code REFEREE} sull'invitato, {@code REFERRER} sull'invitante
 * (chiavi = i due {@code memberId}), entrambi figli dell'azione così il tracciato li mostra insieme. Il premio lo
 * decidono le campagne {@code CMP-REFERRAL-*} tramite il ponte.
 */
@Service
public class ReferralService {

    public static final String ROLE_REFERRER = "REFERRER";
    public static final String ROLE_REFEREE = "REFEREE";
    private static final int TOP_REFERRERS = 5;
    private static final int RECENT_LINKS = 50;

    private final ReferralRepository referrals;
    private final MemberRepository members;
    private final LhEventFactory events;
    private final OutboxWriter outbox;
    private final Clock clock;
    private final String qualifyingActionType;

    public ReferralService(ReferralRepository referrals, MemberRepository members, LhEventFactory events,
                           OutboxWriter outbox, Clock clock,
                           @Value("${loyaltyhub.referral.qualifying-action-type:purchase.completed}") String qualifyingActionType) {
        this.referrals = referrals;
        this.members = members;
        this.events = events;
        this.outbox = outbox;
        this.clock = clock;
        this.qualifyingActionType = qualifyingActionType;
    }

    /** Tipo azione (forma breve) che completa il referral. */
    public String qualifyingActionType() {
        return qualifyingActionType;
    }

    /**
     * Da chiamare nella transazione del consumo di un'azione: se è quella qualificante e il membro ha un referral
     * aperto, lo chiude e accoda i due fatti. L'UPDATE condizionale garantisce un solo completamento.
     */
    public void onAction(LhEvent<?> action) {
        String memberId = action.memberId();
        if (memberId == null || !(LhEventTypes.Action.PREFIX + qualifyingActionType).equals(action.type())) {
            return;
        }
        Optional<String> referrer = referrals.complete(memberId, clock.instant());
        if (referrer.isEmpty()) {
            return;
        }
        String referrerId = referrer.get();
        outbox.write(events.childOf(action, LhEventTypes.Fact.REFERRAL_COMPLETED,
                data(ROLE_REFEREE, referrerId, action.id())));
        outbox.write(events.childForSubject(action, LhEventTypes.Fact.REFERRAL_COMPLETED, "member:" + referrerId,
                data(ROLE_REFERRER, memberId, action.id())));
    }

    public Overview overview() {
        long invited = referrals.countInvited();
        long completed = referrals.countCompleted();
        double rate = invited == 0 ? 0.0 : Math.round(completed * 1000.0 / invited) / 1000.0;
        return new Overview(invited, completed, invited - completed, rate, qualifyingActionType,
                referrals.topReferrers(TOP_REFERRERS), referrals.recentLinks(RECENT_LINKS));
    }

    public List<ReferralLink> referralsOf(String memberId) {
        requireMember(memberId);
        return referrals.invitedBy(memberId);
    }

    public PortalReferral portal(String memberId) {
        Member m = requireMember(memberId);
        List<PortalInvitee> invited = referrals.invitedBy(memberId).stream()
                .map(l -> new PortalInvitee(nicknameOf(l), l.status(), l.registeredAt(), l.completedAt()))
                .toList();
        long completed = invited.stream().filter(i -> "COMPLETED".equals(i.status())).count();
        String code = m.referralCode();
        String shareUrl = code == null ? null : "/portal/join?ref=" + URLEncoder.encode(code, StandardCharsets.UTF_8);
        return new PortalReferral(code, shareUrl, qualifyingActionType, invited, completed);
    }

    private Member requireMember(String memberId) {
        return members.findById(memberId).orElseThrow(() -> LhException.notFound("Membro non trovato: " + memberId));
    }

    /** Il portale mostra all'invitante solo il nickname dell'amico (come le classifiche). */
    private static String nicknameOf(ReferralLink l) {
        if (l.refereeNickname() != null && !l.refereeNickname().isBlank()) {
            return l.refereeNickname();
        }
        return "Amico " + l.refereeId().substring(l.refereeId().length() - 3);
    }

    private static Map<String, Object> data(String role, String counterpartMemberId, String qualifyingActionId) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("role", role);
        d.put("counterpartMemberId", counterpartMemberId);
        d.put("qualifyingActionId", qualifyingActionId);
        return d;
    }
}
