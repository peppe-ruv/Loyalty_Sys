package io.loyaltyhub.wallet.api;

import io.loyaltyhub.wallet.application.WalletQueryService;
import io.loyaltyhub.wallet.domain.LedgerEntry;
import io.loyaltyhub.wallet.domain.Tier;
import io.loyaltyhub.wallet.infra.LedgerRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Gestione wallet (docs/servizi/wallet-service.md §3): saldi, movimenti, scala dei livelli. */
@RestController
@RequestMapping("/v1")
public class WalletsController {

    private final WalletQueryService query;
    private final LedgerRepository ledger;

    public WalletsController(WalletQueryService query, LedgerRepository ledger) {
        this.query = query;
        this.ledger = ledger;
    }

    @GetMapping("/wallets/{memberId}")
    public WalletView wallet(@PathVariable String memberId) {
        return query.wallet(memberId);
    }

    @GetMapping("/wallets/{memberId}/ledger")
    public List<LedgerEntry> ledger(
            @PathVariable String memberId,
            @RequestParam(required = false) String currency,
            @RequestParam(defaultValue = "100") int limit) {
        return ledger.listByMember(memberId, currency, Math.min(Math.max(limit, 1), 500));
    }

    @GetMapping("/tiers")
    public List<Tier> tiers() {
        return query.tierScale();
    }
}
