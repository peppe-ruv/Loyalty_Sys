package io.loyaltyhub.reward.api;

import io.loyaltyhub.common.web.PageResponse;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.reward.application.RedemptionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Richieste premio per il backoffice (docs/servizi/reward-service.md §3; BO-13): elenco con filtri, dettaglio con
 * cronologia, evasione manuale, annullo con rimborso e nuovo tentativo di evasione ({@code redemption.handle}).
 */
@RestController
@RequestMapping("/v1/redemptions")
public class RedemptionsController {

    public record FulfilRequest(String note, String tracking) {
    }

    public record CancelRequest(String reason) {
    }

    private final RedemptionService redemptions;

    public RedemptionsController(RedemptionService redemptions) {
        this.redemptions = redemptions;
    }

    @GetMapping
    public PageResponse<RedemptionService.RedemptionView> list(@RequestParam(required = false) String status,
                                                               @RequestParam(required = false) String fulfilment,
                                                               @RequestParam(required = false) String memberId,
                                                               @RequestParam(required = false) String rewardCode,
                                                               @RequestParam(required = false) Boolean needsAttention,
                                                               @RequestParam(required = false) Instant from,
                                                               @RequestParam(required = false) Instant to,
                                                               @RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(defaultValue = "20") int size) {
        return redemptions.search(status, fulfilment, memberId, rewardCode, needsAttention, from, to, page, size);
    }

    @GetMapping("/{id}")
    public RedemptionService.RedemptionView get(@PathVariable String id) {
        return redemptions.view(id);
    }

    @PostMapping("/{id}/fulfil")
    @RequiresRole({Role.ADMIN, Role.CARE})
    public RedemptionService.RedemptionView fulfil(@PathVariable String id, @RequestBody FulfilRequest r) {
        return redemptions.fulfilManually(id, r == null ? null : r.note(), r == null ? null : r.tracking());
    }

    @PostMapping("/{id}/cancel")
    @RequiresRole({Role.ADMIN, Role.CARE})
    public RedemptionService.RedemptionView cancel(@PathVariable String id, @RequestBody CancelRequest r) {
        return redemptions.cancelWithRefund(id, r == null ? null : r.reason());
    }

    @PostMapping("/{id}/retry-fulfilment")
    @RequiresRole({Role.ADMIN, Role.CARE})
    public RedemptionService.RedemptionView retry(@PathVariable String id) {
        return redemptions.retryFulfilment(id);
    }
}
