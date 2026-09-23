package io.loyaltyhub.wallet.api;

import io.loyaltyhub.wallet.application.WalletQueryService;
import io.loyaltyhub.wallet.domain.LedgerEntry;
import io.loyaltyhub.wallet.domain.PointsLot;
import io.loyaltyhub.wallet.domain.Tier;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.wallet.application.WalletService;
import io.loyaltyhub.wallet.infra.LedgerRepository;
import io.loyaltyhub.wallet.infra.PointsLotRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Gestione wallet (docs/servizi/wallet-service.md §3): saldi, movimenti, scala dei livelli. */
@RestController
@RequestMapping("/v1")
public class WalletsController {

    private final WalletQueryService query;
    private final WalletService walletService;
    private final LedgerRepository ledger;
    private final PointsLotRepository lots;

    public WalletsController(WalletQueryService query, WalletService walletService, LedgerRepository ledger, PointsLotRepository lots) {
        this.query = query;
        this.walletService = walletService;
        this.ledger = ledger;
        this.lots = lots;
    }

    /** Lotto esposto (docs §3): senza {@code ledger_entry_id} e {@code member_id}, interni. */
    public record LotView(String id, String currency, long amount, long remaining, String status,
                          Instant earnedAt, Instant availableAt, Instant expiresAt) {
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

    @GetMapping("/wallets/{memberId}/lots")
    public List<LotView> lots(@PathVariable String memberId) {
        return lots.findOpenByMember(memberId).stream().map(WalletsController::toView).toList();
    }

    @GetMapping("/tiers")
    public List<Tier> tiers() {
        return query.tierScale();
    }

    public record AdjustmentRequest(String currency, String direction, long amount, String reason, String note) {
    }

    public record AdjustmentResponse(String ledgerEntryId, long balanceAfter) {
    }

    @PostMapping("/wallets/{memberId}/adjustments")
    @RequiresRole({Role.CARE, Role.ADMIN})
    public AdjustmentResponse adjust(
            @PathVariable String memberId,
            @RequestBody AdjustmentRequest request) {
        return walletService.adjustBalance(memberId, request.currency(), request.direction(), request.amount(), request.reason(), request.note());
    }

    private static LotView toView(PointsLot l) {
        return new LotView(l.id(), l.currency(), l.amount(), l.remaining(), l.status(),
                l.earnedAt(), l.availableAt(), l.expiresAt());
    }
}
