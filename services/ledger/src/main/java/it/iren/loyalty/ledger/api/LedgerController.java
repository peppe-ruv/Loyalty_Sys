package it.iren.loyalty.ledger.api;

import it.iren.loyalty.common.event.Currency;
import it.iren.loyalty.ledger.domain.Balance;
import it.iren.loyalty.ledger.domain.LedgerService;
import it.iren.loyalty.ledger.domain.Movement;
import it.iren.loyalty.ledger.domain.Repositories;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** API interna del ledger (usata da rules-engine, catalog-redemption, backoffice/customer care per accrediti manuali RF-18). */
@RestController
@RequestMapping("/v1/ledger")
public class LedgerController {
    public record ManualPosting(@NotBlank String memberId, @NotBlank String actionKey, Currency currency, long amount, @NotBlank String reason) {}
    public record Debit(@NotBlank String memberId, @NotBlank String actionKey, @Positive long points, @NotBlank String reason) {}

    private final LedgerService ledger;
    private final Repositories.BalanceRepository balances;
    private final Repositories.MovementRepository movements;

    public LedgerController(LedgerService ledger, Repositories.BalanceRepository balances, Repositories.MovementRepository movements) {
        this.ledger = ledger; this.balances = balances; this.movements = movements;
    }

    @GetMapping("/members/{memberId}/balances")
    public List<Balance> balances(@PathVariable String memberId) { return balances.findByMemberId(memberId); }

    @GetMapping("/members/{memberId}/movements")
    public List<Movement> movements(@PathVariable String memberId) { return movements.findTop100ByMemberIdOrderByCreatedAtDesc(memberId); }

    @PostMapping("/postings")
    public List<Movement> post(@RequestBody @Valid ManualPosting p) {
        return ledger.post(p.memberId(), p.actionKey(), List.of(new LedgerService.Posting(p.currency(), p.amount(), p.reason(), "manual", null)));
    }

    @PostMapping("/debits")
    public Movement debit(@RequestBody @Valid Debit d) { return ledger.debit(d.memberId(), d.actionKey(), d.points(), d.reason()); }

    @PostMapping("/reversals/{actionKey}")
    public List<Movement> reverse(@PathVariable String actionKey, @RequestParam(defaultValue = "REVERSAL") String reason) {
        return ledger.reverse(actionKey, reason);
    }

    @ExceptionHandler(LedgerService.InsufficientBalanceException.class)
    public ResponseEntity<Map<String, String>> insufficient(LedgerService.InsufficientBalanceException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "INSUFFICIENT_BALANCE", "detail", e.getMessage()));
    }
}
