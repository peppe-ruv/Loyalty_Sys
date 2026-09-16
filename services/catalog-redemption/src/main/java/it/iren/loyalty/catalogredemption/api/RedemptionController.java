package it.iren.loyalty.catalogredemption.api;

import it.iren.loyalty.catalogredemption.domain.RedemptionService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/redemptions")
public class RedemptionController {
    public record RedeemRequest(@NotBlank String memberId, @NotBlank String memberTierOrder, UUID rewardId) {}
    private final RedemptionService service;
    public RedemptionController(RedemptionService service) { this.service = service; }

    @PostMapping
    public RedemptionService.Result redeem(@RequestBody RedeemRequest r) { return service.redeem(r.memberId(), r.memberTierOrder(), r.rewardId()); }

    @ExceptionHandler(RedemptionService.RedemptionRejected.class)
    public ResponseEntity<Map<String, String>> rejected(RedemptionService.RedemptionRejected e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
