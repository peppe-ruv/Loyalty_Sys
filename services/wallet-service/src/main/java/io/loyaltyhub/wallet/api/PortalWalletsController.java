package io.loyaltyhub.wallet.api;

import io.loyaltyhub.wallet.application.WalletQueryService;
import io.loyaltyhub.wallet.domain.Tier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping("/tiers")
    public List<Tier> tiers() {
        return query.tierScale();
    }
}
