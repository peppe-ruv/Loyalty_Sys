package io.loyaltyhub.ledger.api;

import io.loyaltyhub.common.event.Currency;
import io.loyaltyhub.ledger.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** API interna del ledger (rules-engine, catalog-redemption, backoffice/customer care: accrediti manuali RF-18, blocchi e trasferimenti RF-88, wallet RF-87). */
@RestController
@RequestMapping("/v1/ledger")
public class LedgerController {
    public record ManualPosting(@NotBlank String memberId, @NotBlank String actionKey, Currency currency, long amount, @NotBlank String reason, Integer lockDays, String expiresAt, String pendingUntil) {}
    public record Debit(@NotBlank String memberId, @NotBlank String actionKey, @Positive long points, @NotBlank String reason, Currency currency) {}
    public record Block(@NotBlank String memberId, @NotBlank String actionKey, Currency currency, @Positive long amount, @NotBlank String reason) {}
    public record Transfer(@NotBlank String fromMemberId, @NotBlank String toMemberId, Currency currency, @Positive long amount, @NotBlank String transferKey, String comment) {}

    private final LedgerService ledger;
    private final Repositories.BalanceRepository balances;
    private final Repositories.MovementRepository movements;
    private final WalletTypeSource wallets;

    public LedgerController(LedgerService ledger, Repositories.BalanceRepository balances, Repositories.MovementRepository movements, WalletTypeSource wallets) {
        this.ledger = ledger; this.balances = balances; this.movements = movements; this.wallets = wallets;
    }

    @GetMapping("/wallets")
    public List<WalletType> wallets() { return wallets.all(); }

    @GetMapping("/members/{memberId}/balances")
    public List<Balance> balances(@PathVariable String memberId) { return balances.findByMemberId(memberId); }

    /** Vista wallet per le espressioni delle campagne (RF-84): attivo, maturato, speso, sospeso, bloccato, scaduto. */
    @GetMapping("/members/{memberId}/wallets")
    public Map<String, Map<String, Long>> walletViews(@PathVariable String memberId) {
        Map<String, Map<String, Long>> out = new java.util.HashMap<>();
        for (Balance b : balances.findByMemberId(memberId))
            out.put(b.getWallet(), Map.of("active", b.getAvailable(), "earned", b.getEarnedTotal(), "spent", b.getSpentTotal(), "pending", b.getPending(), "blocked", b.getBlocked(), "expired", b.getExpiredTotal()));
        return out;
    }

    @GetMapping("/members/{memberId}/movements")
    public List<Movement> movements(@PathVariable String memberId) { return movements.findTop100ByMemberIdOrderByCreatedAtDesc(memberId); }

    @PostMapping("/postings")
    public List<Movement> post(@RequestBody @Valid ManualPosting p) {
        return ledger.post(p.memberId(), p.actionKey(), List.of(new LedgerService.Posting(p.currency() == null ? Currency.PREMIO : p.currency(), p.amount(), p.reason(), "manual",
                blank(p.expiresAt()) ? null : Instant.parse(p.expiresAt()), p.lockDays() == null ? 0 : p.lockDays(), blank(p.pendingUntil()) ? null : Instant.parse(p.pendingUntil()))));
    }

    @PostMapping("/debits")
    public Movement debit(@RequestBody @Valid Debit d) { return ledger.debit(d.memberId(), d.currency() == null ? Currency.PREMIO : d.currency(), d.actionKey(), d.points(), d.reason()); }

    @PostMapping("/blocks")
    public ResponseEntity<Void> block(@RequestBody @Valid Block b, @RequestParam(defaultValue = "false") boolean unblock) {
        ledger.block(b.memberId(), b.currency() == null ? Currency.PREMIO : b.currency(), b.amount(), b.actionKey(), b.reason(), unblock);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/transfers")
    public List<Movement> transfer(@RequestBody @Valid Transfer t) {
        return ledger.transfer(t.fromMemberId(), t.toMemberId(), t.currency() == null ? Currency.PREMIO : t.currency(), t.amount(), t.transferKey(), t.comment() == null ? "" : t.comment());
    }

    @PostMapping("/reversals/{actionKey}")
    public List<Movement> reverse(@PathVariable String actionKey, @RequestParam(defaultValue = "REVERSAL") String reason) {
        return ledger.reverse(actionKey, reason);
    }

    @ExceptionHandler(LedgerService.InsufficientBalanceException.class)
    public ResponseEntity<Map<String, String>> insufficient(LedgerService.InsufficientBalanceException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "INSUFFICIENT_BALANCE", "detail", e.getMessage()));
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
