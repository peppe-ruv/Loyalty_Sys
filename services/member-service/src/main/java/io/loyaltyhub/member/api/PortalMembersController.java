package io.loyaltyhub.member.api;

import io.loyaltyhub.common.web.MemberEndpoint;
import io.loyaltyhub.common.web.MemberPrincipal;
import io.loyaltyhub.member.application.MemberService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Profilo del portale con l'id nel percorso (docs/09 PT-08, F-MBR-07): <strong>percorso legacy, deprecato</strong>,
 * valido solo nel profilo {@code demo} (regola 6-bis) e mai rimosso (ADR-038). Il portale usa
 * {@link PortalMeController} ({@code /v1/portal/me/profile}); in {@code enterprise} un id nel percorso dà
 * {@code 403 MEMBER_FROM_TOKEN} (ADR-048, Q-553). L'id di percorso è letto da {@code EndpointAccessInterceptor}
 * ({@code demoPathVariable}) e arriva nel {@link MemberPrincipal}; in {@code demo} due fonti diverse dànno
 * {@code 400 MEMBER_MISMATCH}.
 */
@RestController
@RequestMapping("/v1/portal/members")
public class PortalMembersController {

    private final MemberService service;

    public PortalMembersController(MemberService service) {
        this.service = service;
    }

    /** @deprecated usa {@code GET /v1/portal/me/profile}. Solo profilo demo. */
    @Deprecated
    @GetMapping("/{id}")
    @MemberEndpoint(demoPathVariable = "id")
    public PortalProfileView get(@PathVariable String id, MemberPrincipal principal) {
        return service.portalProfile(principal.requireParam());
    }

    /** @deprecated usa {@code PATCH /v1/portal/me/profile}. Solo profilo demo. */
    @Deprecated
    @PatchMapping("/{id}")
    @MemberEndpoint(demoPathVariable = "id")
    public PortalProfileView update(@PathVariable String id, MemberPrincipal principal,
                                    @RequestBody PortalProfileRequest request) {
        String memberId = principal.requireParam();
        service.update(memberId, request.toUpdate());
        return service.portalProfile(memberId);
    }
}
