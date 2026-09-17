package it.iren.loyalty.catalogredemption.api;

import it.iren.loyalty.catalogredemption.domain.CouponPool;
import it.iren.loyalty.catalogredemption.domain.RedemptionService;
import it.iren.loyalty.catalogredemption.domain.RedemptionState;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Riscatti (RF-15..RF-17), assegnazioni senza punti (RF-76), transizioni di stato per cliente/operatore, lotti di codici (RF-74). */
@RestController
@RequestMapping("/v1")
public class RedemptionController {
    public record RedeemRequest(@NotBlank String memberId, int memberTierOrder, Set<String> memberSegments, long premioAvailable, UUID rewardId) {}
    public record GrantRequest(@NotBlank String memberId, UUID rewardId, @NotBlank String grantKey) {}
    public record Transition(@NotBlank String to, @NotBlank String actor, boolean byMember) {}

    private final RedemptionService service;
    private final CouponPool coupons;
    private final org.springframework.web.client.RestClient ledger;
    public RedemptionController(RedemptionService service, CouponPool coupons, org.springframework.web.client.RestClient.Builder b) {
        this.service = service; this.coupons = coupons;
        this.ledger = b.baseUrl(System.getenv().getOrDefault("LEDGER_URL", "http://ledger:8083")).build();
    }

    @PostMapping("/redemptions")
    public RedemptionService.Result redeem(@RequestBody RedeemRequest r) {
        return service.redeem(r.memberId(), r.memberTierOrder(), r.memberSegments() == null ? Set.of() : r.memberSegments(), r.premioAvailable(), r.rewardId());
    }

    @PostMapping("/grants")
    public RedemptionService.Result grant(@RequestBody GrantRequest g) { return service.grant(g.memberId(), g.rewardId(), g.grantKey()); }

    /** Cliente: annullo (RF-17). Operatore/fornitore: IN_DELIVERY, DELIVERED, USED (RF-16, RF-76). */
    @PostMapping("/redemptions/{id}/transitions")
    public Map<String, String> transition(@PathVariable UUID id, @RequestBody Transition t) {
        return Map.of("status", service.transition(id, RedemptionState.valueOf(t.to()), t.actor(), t.byMember()));
    }

    public record BulkTransition(List<UUID> ids, @NotBlank String to, @NotBlank String actor) {}
    @PostMapping("/redemptions/transitions")
    public Map<UUID, String> bulk(@RequestBody BulkTransition t) { return service.transitionAll(t.ids(), RedemptionState.valueOf(t.to()), t.actor()); }

    /** Paga con i punti (RF-104): scala le unità necessarie a coprire l'importo e restituisce lo sconto da applicare al carrello. */
    public record PayWithPoints(@NotBlank String memberId, @NotBlank String orderRef, java.math.BigDecimal amountEur, Long units) {}
    @PostMapping("/pay-with-points")
    public Map<String, Object> payWithPoints(@RequestBody PayWithPoints p) {
        var conv = new it.iren.loyalty.catalogredemption.domain.UnitsConversion(new java.math.BigDecimal(System.getenv().getOrDefault("EUR_PER_UNIT", "0.01")), 100, 0, 100);
        long units = p.units() != null ? p.units() : conv.unitsFor(p.amountEur());
        // Sotto il taglio minimo non si paga con i punti: meglio dirlo che scalare un importo sbagliato.
        if (units <= 0) throw new RedemptionService.RedemptionRejected("AMOUNT_BELOW_MINIMUM");
        var value = conv.valueOf(units);
        // Unità chieste esplicitamente dal chiamante: se valgono più del carrello, il membro
        // ci rimetterebbe la differenza. Si rifiuta invece di consumarle in silenzio.
        if (value.compareTo(p.amountEur()) > 0) throw new RedemptionService.RedemptionRejected("UNITS_EXCEED_AMOUNT");
        try {
            ledger.post().uri("/v1/ledger/debits").body(Map.of("memberId", p.memberId(), "actionKey", "pay:" + p.orderRef() + ":DEBIT", "points", units, "reason", "PAY_WITH_POINTS")).retrieve().toBodilessEntity();
        } catch (org.springframework.web.client.HttpClientErrorException.Conflict e) { throw new RedemptionService.RedemptionRejected("INSUFFICIENT_BALANCE"); }
        return Map.of("orderRef", p.orderRef(), "units", units, "discountEur", value, "reversalKey", "pay:" + p.orderRef() + ":DEBIT");
    }

    @PostMapping(value = "/coupon-pools/{poolId}/codes", consumes = "text/plain")
    public Map<String, Object> loadCodes(@PathVariable String poolId, @RequestBody String body) {
        int n = coupons.load(poolId, Arrays.asList(body.split("\r?\n")));
        return Map.of("loaded", n, "remaining", coupons.remaining(poolId));
    }

    @GetMapping("/coupon-pools/{poolId}")
    public Map<String, Object> pool(@PathVariable String poolId) { return Map.of("poolId", poolId, "remaining", coupons.remaining(poolId)); }

    @ExceptionHandler(RedemptionService.RedemptionRejected.class)
    public ResponseEntity<Map<String, String>> rejected(RedemptionService.RedemptionRejected e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
