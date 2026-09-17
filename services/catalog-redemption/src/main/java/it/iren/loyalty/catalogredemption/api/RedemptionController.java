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
    public RedemptionController(RedemptionService service, CouponPool coupons) { this.service = service; this.coupons = coupons; }

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
