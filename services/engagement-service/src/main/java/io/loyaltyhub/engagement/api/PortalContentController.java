package io.loyaltyhub.engagement.api;

import io.loyaltyhub.common.web.MemberEndpoint;
import io.loyaltyhub.common.web.MemberPrincipal;
import io.loyaltyhub.engagement.application.ContentService;
import io.loyaltyhub.engagement.application.ContentService.ContentDisplay;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Contenuti del portale (docs/servizi/engagement-service.md §3; PT-01, PT-03, PT-05, PT-06): elenco già ordinato e
 * limitato per il membro; per {@code WIN} {@code prizeCode} sceglie la card del premio vinto. Il membro viene solo dal
 * token (Q-410, ADR-048, docs/06 §3.4): nessun {@code memberId} in query. In {@code demo} lo legge l'interceptor
 * ({@code memberId} o {@code X-LH-Member}); un operatore ({@code OPTIONAL}, BO-17) ottiene la vista generica.
 */
@RestController
@RequestMapping("/v1/portal/content")
public class PortalContentController {

    private final ContentService service;

    public PortalContentController(ContentService service) {
        this.service = service;
    }

    @GetMapping
    @Transactional(readOnly = true)
    @MemberEndpoint(MemberEndpoint.Mode.OPTIONAL)
    public List<ContentDisplay> content(MemberPrincipal principal,
                                        @RequestParam(required = false) String placement,
                                        @RequestParam(required = false) String prizeCode) {
        if (principal.origin() == MemberPrincipal.Origin.NONE) {
            return service.portalGeneric(placement, prizeCode);
        }
        return service.portal(principal.idOrNull(), placement, prizeCode);
    }
}
