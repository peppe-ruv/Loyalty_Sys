package io.loyaltyhub.reward.api;

import io.loyaltyhub.common.web.MemberEndpoint;
import io.loyaltyhub.common.web.MemberPrincipal;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.reward.application.PortalCatalogService;
import io.loyaltyhub.reward.domain.Category;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Catalogo premi del portale (docs/servizi/reward-service.md §3 "Portale"; PT-03, PT-04). Il membro non è più un
 * parametro: lo risolve {@code EndpointAccessInterceptor} dal token (profilo {@code enterprise}) o da {@code X-LH-Member}
 * / {@code memberId} (profilo {@code demo}) e lo consegna come {@link MemberPrincipal} (Q-410, ADR-048, docs/06 §3.4).
 * Il catalogo è {@code OPTIONAL}: un operatore (BO-17 mostra l'anteprima) riceve la vista generica, senza livello né
 * segmenti né limiti per membro.
 */
@RestController
@RequestMapping("/v1/portal")
public class PortalCatalogController {

    private final PortalCatalogService portal;

    public PortalCatalogController(PortalCatalogService portal) {
        this.portal = portal;
    }

    @GetMapping("/catalog")
    @MemberEndpoint(MemberEndpoint.Mode.OPTIONAL)
    public PortalCatalogService.PortalCatalog catalog(MemberPrincipal principal) {
        return portal.catalog(principal.idOrNull());
    }

    @GetMapping("/rewards/{code}")
    @MemberEndpoint(MemberEndpoint.Mode.OPTIONAL)
    public PortalCatalogService.PortalRewardDetail reward(@PathVariable String code, MemberPrincipal principal) {
        return portal.detail(code, principal.idOrNull());
    }

    /**
     * Categorie per il catalogo del portale (B4, Q-410): stessa lista di {@code GET /v1/reward-categories}, uguale per
     * tutti, aperta ai membri perché non porta dati di un membro. Il backoffice continua a usare
     * {@code /v1/reward-categories}.
     */
    @GetMapping("/reward-categories")
    @RequiresRole(value = {Role.ADMIN, Role.MARKETING, Role.LEGAL, Role.CARE, Role.ANALYST}, members = true)
    public List<Category> categories() {
        return portal.categories();
    }
}
