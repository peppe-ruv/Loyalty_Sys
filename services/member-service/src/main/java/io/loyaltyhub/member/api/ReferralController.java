package io.loyaltyhub.member.api;

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
    public Overview overview() {
        return service.overview();
    }

    @GetMapping("/v1/members/{id}/referrals")
    public List<ReferralLink> referrals(@PathVariable String id) {
        return service.referralsOf(id);
    }

    @GetMapping("/v1/portal/members/{id}/referral")
    public PortalReferral portal(@PathVariable String id) {
        return service.portal(id);
    }
}
