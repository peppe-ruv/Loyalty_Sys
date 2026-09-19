package io.loyaltyhub.campaign.api;

import io.loyaltyhub.campaign.application.CampaignAdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Elenco "come guadagnare" del portale (docs/servizi/campaign-service.md §3). */
@RestController
@RequestMapping("/v1/portal/campaigns")
public class PortalCampaignsController {

    private final CampaignAdminService service;

    public PortalCampaignsController(CampaignAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<PortalCampaignView> list(@RequestParam(required = false) String memberId) {
        return service.portal(memberId);
    }
}
