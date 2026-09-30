package io.loyaltyhub.member.api;

import io.loyaltyhub.common.web.MemberEndpoint;
import io.loyaltyhub.common.web.MemberPrincipal;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.member.api.ReferralViews.Overview;
import io.loyaltyhub.member.api.ReferralViews.PortalReferral;
import io.loyaltyhub.member.api.ReferralViews.ReferralLink;
import io.loyaltyhub.member.application.ReferralService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Referral (docs/servizi/member-service.md §3, F-REF-01/02): panoramica BO-17, invitati di un membro, PT-11. */
@RestController
public class ReferralController {

    private final ReferralService service;

    public ReferralController(ReferralService service) {
        this.service = service;
    }

    @GetMapping("/v1/referral/overview")
    @RequiresRole({Role.ADMIN, Role.MARKETING, Role.LEGAL, Role.CARE, Role.ANALYST})
    public Overview overview() {
        return service.overview();
    }

    @GetMapping("/v1/members/{id}/referrals")
    @RequiresRole({Role.ADMIN, Role.MARKETING, Role.LEGAL, Role.CARE, Role.ANALYST})
    public List<ReferralLink> referrals(@PathVariable String id) {
        return service.referralsOf(id);
    }

    /**
     * PT-11 con l'id nel percorso: <strong>percorso legacy, deprecato</strong>, valido solo nel profilo {@code demo};
     * il portale usa {@code GET /v1/portal/me/referral} ({@link PortalMeController}, ADR-048).
     *
     * @deprecated usa {@code GET /v1/portal/me/referral}
     */
    @Deprecated
    @GetMapping("/v1/portal/members/{id}/referral")
    @MemberEndpoint(demoPathVariable = "id")
    public PortalReferral portal(@PathVariable String id, MemberPrincipal principal) {
        return service.portal(principal.requireParam());
    }
}
