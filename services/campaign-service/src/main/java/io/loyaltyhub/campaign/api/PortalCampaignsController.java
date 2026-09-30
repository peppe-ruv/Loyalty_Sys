package io.loyaltyhub.campaign.api;

import io.loyaltyhub.campaign.application.CampaignAdminService;
import io.loyaltyhub.common.web.MemberEndpoint;
import io.loyaltyhub.common.web.MemberPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Elenco "come guadagnare" del portale (docs/servizi/campaign-service.md §3). Il membro viene solo dal token (Q-410,
 * ADR-048, docs/06 §3.4): nessun {@code memberId} in query, che in {@code enterprise} dà {@code 400 MEMBER_FROM_TOKEN}.
 */
@RestController
@RequestMapping("/v1/portal/campaigns")
public class PortalCampaignsController {

    private final CampaignAdminService service;

    public PortalCampaignsController(CampaignAdminService service) {
        this.service = service;
    }

    /**
     * Senza {@code codes}: le campagne visibili nel portale. Con {@code codes}: quelle campagne LIVE anche se non
     * elencate in "Guadagna", per spiegare un meccanismo coi valori reali (PT-11 referral).
     *
     * <p>{@code OPTIONAL}: con un membro (il token in {@code enterprise}; {@code X-LH-Member} o il {@code memberId}
     * esplicito in {@code demo}) le campagne sono filtrate per il suo pubblico (livello, segmenti); senza membro, cioè un
     * operatore (BO-17 legge le regole con {@code codes=}, Q-560), un membro non ancora legato o anonimizzato, la vista
     * generica.
     */
    @GetMapping
    @MemberEndpoint(MemberEndpoint.Mode.OPTIONAL)
    public List<PortalCampaignView> list(MemberPrincipal principal, @RequestParam(required = false) List<String> codes) {
        return service.portal(principal.idOrNull(), codes);
    }
}
