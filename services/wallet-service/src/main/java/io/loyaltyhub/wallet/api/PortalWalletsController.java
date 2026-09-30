package io.loyaltyhub.wallet.api;

import io.loyaltyhub.common.web.MemberEndpoint;
import io.loyaltyhub.common.web.MemberPrincipal;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.wallet.application.EditionService;
import io.loyaltyhub.wallet.application.WalletQueryService;
import io.loyaltyhub.wallet.domain.Edition;
import io.loyaltyhub.wallet.domain.Tier;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/** Portale (docs/servizi/wallet-service.md §3): saldo del membro e scala dei livelli (PT-08). */
@RestController
@RequestMapping("/v1/portal")
public class PortalWalletsController {

    private final WalletQueryService query;
    private final EditionService editionService;

    public PortalWalletsController(WalletQueryService query, EditionService editions) {
        this.query = query;
        this.editionService = editions;
    }

    /**
     * Percorso legacy con l'id nel percorso, valido solo nel profilo {@code demo} (Q-410, ADR-048): l'id lo legge
     * l'interceptor ({@code demoPathVariable}) e arriva nel {@link MemberPrincipal}. In {@code enterprise} dà
     * {@code 403 MEMBER_FROM_TOKEN}: si usa {@code GET /v1/portal/me/wallet}. Non si rimuove mai.
     *
     * @deprecated solo profilo demo; il membro viene dal token: {@code /v1/portal/me/wallet}
     */
    @Deprecated
    @GetMapping("/wallets/{memberId}")
    @MemberEndpoint(demoPathVariable = "memberId")
    @Parameter(name = "memberId", in = ParameterIn.PATH, required = true, deprecated = true,
            description = "solo profilo demo", schema = @Schema(type = "string"))
    public WalletView wallet(MemberPrincipal principal) {
        return query.wallet(principal.requireParam());
    }

    /**
     * Livello della scala del portale (wallet-service §3, PT-08): {@code {code, name, threshold, multiplier, benefits[],
     * color}}. {@code rank}, {@code thresholdSts} e {@code icon} restano per compatibilità con i client esistenti.
     */
    public record PortalTier(String code, String name, long threshold, BigDecimal multiplier, List<String> benefits,
                             String color, int rank, long thresholdSts, String icon) {
        static PortalTier of(Tier t) {
            return new PortalTier(t.code(), t.name(), t.thresholdSts(), t.multiplier(), t.benefits(), t.color(),
                    t.rank(), t.thresholdSts(), t.icon());
        }
    }

    @GetMapping("/tiers")
    @RequiresRole(value = {Role.ADMIN, Role.MARKETING, Role.LEGAL, Role.CARE, Role.ANALYST}, members = true) // Q-410: uguale per tutti
    public List<PortalTier> tiers() {
        return query.tierScale().stream().map(PortalTier::of).toList();
    }

    /**
     * Edizioni per la home del portale (B3, Q-410): stessa lista di {@code GET /v1/editions}, uguale per tutti, aperta
     * ai membri perché non porta dati di un membro. Il backoffice continua a usare {@code /v1/editions}.
     */
    @GetMapping("/editions")
    @RequiresRole(value = {Role.ADMIN, Role.MARKETING, Role.LEGAL, Role.CARE, Role.ANALYST}, members = true)
    public List<Edition> editions() {
        return editionService.list();
    }
}
