package io.loyaltyhub.reward.api;

import io.loyaltyhub.common.web.PageResponse;
import io.loyaltyhub.reward.application.RedemptionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** Richieste premio per il backoffice (docs/servizi/reward-service.md §3; BO-13). Evasione e annullo: M4.4. */
@RestController
@RequestMapping("/v1/redemptions")
public class RedemptionsController {

    private final RedemptionService redemptions;

    public RedemptionsController(RedemptionService redemptions) {
        this.redemptions = redemptions;
    }

    @GetMapping
    public PageResponse<RedemptionService.RedemptionView> list(@RequestParam(required = false) String status,
                                                               @RequestParam(required = false) String memberId,
                                                               @RequestParam(required = false) String rewardCode,
                                                               @RequestParam(required = false) Boolean needsAttention,
                                                               @RequestParam(required = false) Instant from,
                                                               @RequestParam(required = false) Instant to,
                                                               @RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(defaultValue = "20") int size) {
        return redemptions.search(status, memberId, rewardCode, needsAttention, from, to, page, size);
    }

    @GetMapping("/{id}")
    public RedemptionService.RedemptionView get(@PathVariable String id) {
        return redemptions.view(id);
    }
}
