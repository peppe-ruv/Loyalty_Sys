package io.loyaltyhub.member.api;

import io.loyaltyhub.member.application.MemberService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Profilo del portale (docs/09 PT-08, F-MBR-07): lettura con completezza e modifica dei soli dati personali. */
@RestController
@RequestMapping("/v1/portal/members")
public class PortalMembersController {

    private final MemberService service;

    public PortalMembersController(MemberService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public PortalProfileView get(@PathVariable String id) {
        return service.portalProfile(id);
    }

    @PatchMapping("/{id}")
    public PortalProfileView update(@PathVariable String id, @RequestBody PortalProfileRequest request) {
        service.update(id, request.toUpdate());
        return service.portalProfile(id);
    }
}
