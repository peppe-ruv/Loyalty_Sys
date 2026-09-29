package io.loyaltyhub.member.api;

import io.loyaltyhub.common.web.MemberEndpoint;
import io.loyaltyhub.common.web.MemberPrincipal;
import io.loyaltyhub.member.api.ReferralViews.PortalReferral;
import io.loyaltyhub.member.application.MemberService;
import io.loyaltyhub.member.application.ReferralService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Il profilo e il referral del membro dal token (PT-08, PT-11, F-MBR-07, F2-SEC-09, ADR-048, Q-410): nessun id nel
 * percorso, in query o nel corpo. In {@code enterprise} il membro è quello del token ({@code MemberPrincipal}); in
 * {@code demo} è il {@code memberId} esplicito o l'header {@code X-LH-Member} messo dal BFF (regola 6-bis). Un account
 * senza membro riceve {@code 404 MEMBER_NOT_REGISTERED}: il portale porta alla registrazione ({@code POST /v1/portal/members}).
 */
@RestController
@RequestMapping("/v1/portal/me")
@MemberEndpoint
public class PortalMeController {

    private final MemberService members;
    private final ReferralService referrals;

    public PortalMeController(MemberService members, ReferralService referrals) {
        this.members = members;
        this.referrals = referrals;
    }

    /** Profilo con completezza e stato. */
    @GetMapping("/profile")
    public PortalProfileView profile(MemberPrincipal principal) {
        return members.portalProfile(principal.requireParam());
    }

    /**
     * Modifica dei soli campi di profilo e consensi; l'audit ha attore {@code member:<id>} (Q-556).
     * SPEC-GAP: Q-573 — un {@code memberId} nel corpo non dà {@code 400 MEMBER_FROM_TOKEN} (docs/06 §3.4): il DTO non
     * ha il campo, Jackson lo scarta prima di {@code MemberBodyAdvice}; il membro resta quello del token.
     */
    @PatchMapping("/profile")
    public PortalProfileView updateProfile(MemberPrincipal principal, @RequestBody PortalProfileRequest request) {
        // SPEC-GAP: Q-573 (un memberId nel corpo è scartato dal DTO, non rifiutato)
        String id = principal.requireParam();
        members.update(id, request.toUpdate());
        return members.portalProfile(id);
    }

    /** Codice amico, link di condivisione e invitati (PT-11). */
    @GetMapping("/referral")
    public PortalReferral referral(MemberPrincipal principal) {
        return referrals.portal(principal.requireParam());
    }
}
