package io.loyaltyhub.member.api;

import io.loyaltyhub.common.web.MemberEndpoint;
import io.loyaltyhub.common.web.MemberSubject;
import io.loyaltyhub.member.application.MemberService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.loyaltyhub.member.application.MemberService.PortalRegistration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Registrazione dal portale (PT-16, F-MBR-06, F2-IAM-03, Q-157, ADR-048): il token di un account crea il proprio
 * membro e il legame {@code (issuer, sub)} in una transazione. Idempotente sul {@code sub}: {@code 201} la prima
 * volta, {@code 200} con lo stesso profilo dopo. In {@code demo} (nessun token) crea un nuovo membro, come prima.
 * Un operatore o un token misto non registra membri: {@code 403 MEMBER_REQUIRED}.
 */
@RestController
@RequestMapping("/v1/portal/members")
public class PortalRegistrationController {

    static final URI PROFILE = URI.create("/v1/portal/me/profile");

    private final MemberService service;

    public PortalRegistrationController(MemberService service) {
        this.service = service;
    }

    /** SPEC-GAP: Q-573 — un {@code memberId} nel corpo è ignorato, non {@code 400 MEMBER_FROM_TOKEN}; vedi {@link PortalRegistrationRequest}. */
    @PostMapping
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Membro creato; Location: /v1/portal/me/profile",
                    content = @Content(schema = @Schema(implementation = PortalProfileView.class))),
            @ApiResponse(responseCode = "200", description = "L'account era già registrato: il profilo esistente",
                    content = @Content(schema = @Schema(implementation = PortalProfileView.class)))})
    @MemberEndpoint(MemberEndpoint.Mode.REGISTRATION)
    public ResponseEntity<PortalProfileView> register(MemberSubject subject, @RequestBody PortalRegistrationRequest request) {
        // SPEC-GAP: Q-573 (un memberId nel corpo è scartato dal DTO, non rifiutato)
        PortalRegistration result = service.registerFromPortal(subject, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .location(PROFILE)
                .body(result.profile());
    }
}
