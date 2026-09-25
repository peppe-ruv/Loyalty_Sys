package io.loyaltyhub.wallet.api;

import io.loyaltyhub.wallet.application.WalletQueryService;
import io.loyaltyhub.wallet.domain.Tier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/** Portale (docs/servizi/wallet-service.md §3): saldo del membro e scala dei livelli (PT-08). */
@RestController
@RequestMapping("/v1/portal")
public class PortalWalletsController {

    private final WalletQueryService query;

    public PortalWalletsController(WalletQueryService query) {
        this.query = query;
    }

    @GetMapping("/wallets/{memberId}")
    public WalletView wallet(@PathVariable String memberId) {
        return query.wallet(memberId);
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
    public List<PortalTier> tiers() {
        return query.tierScale().stream().map(PortalTier::of).toList();
    }
}
