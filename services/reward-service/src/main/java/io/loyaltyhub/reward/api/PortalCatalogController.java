package io.loyaltyhub.reward.api;

import io.loyaltyhub.reward.application.PortalCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Catalogo premi del portale (docs/servizi/reward-service.md §3 "Portale"; PT-03, PT-04). */
@RestController
@RequestMapping("/v1/portal")
public class PortalCatalogController {

    private final PortalCatalogService portal;

    public PortalCatalogController(PortalCatalogService portal) {
        this.portal = portal;
    }

    @GetMapping("/catalog")
    public PortalCatalogService.PortalCatalog catalog(@RequestParam(required = false) String memberId) {
        return portal.catalog(memberId);
    }

    @GetMapping("/rewards/{code}")
    public PortalCatalogService.PortalRewardDetail reward(@PathVariable String code,
                                                         @RequestParam(required = false) String memberId) {
        return portal.detail(code, memberId);
    }
}
