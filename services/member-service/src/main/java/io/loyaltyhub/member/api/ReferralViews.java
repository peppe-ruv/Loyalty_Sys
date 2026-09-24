package io.loyaltyhub.member.api;

import java.time.Instant;
import java.util.List;

/** Viste del referral (docs/servizi/member-service.md §3, docs/08 BO-17, docs/09 PT-11). */
public final class ReferralViews {

    private ReferralViews() {
    }

    /** Un legame invitante → invitato. {@code status}: {@code PENDING} finché l'invitato non compie l'azione qualificante. */
    public record ReferralLink(
            String referrerId,
            String referrerName,
            String refereeId,
            String refereeName,
            String refereeNickname,
            String refereeStatus,
            String status,
            Instant registeredAt,
            Instant completedAt
    ) {
    }

    public record TopReferrer(String memberId, String name, long invited, long completed) {
    }

    /** {@code GET /v1/referral/overview}: totali, tasso, top presentatori, legami recenti, regola di completamento. */
    public record Overview(
            long invited,
            long completed,
            long pending,
            double rate,
            String qualifyingActionType,
            List<TopReferrer> topReferrers,
            List<ReferralLink> links
    ) {
    }

    /** Invitato visto dal portale: solo nickname, niente dati personali dell'amico. */
    public record PortalInvitee(String nickname, String status, Instant registeredAt, Instant completedAt) {
    }

    /** {@code GET /v1/portal/members/{id}/referral}: {@code shareUrl} relativo al portale. */
    public record PortalReferral(
            String code,
            String shareUrl,
            String qualifyingActionType,
            List<PortalInvitee> invited,
            long completedCount
    ) {
    }
}
