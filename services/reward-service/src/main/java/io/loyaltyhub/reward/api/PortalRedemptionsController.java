package io.loyaltyhub.reward.api;

import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.reward.application.RedemptionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Richieste premio dal portale (docs/servizi/reward-service.md §3 "Portale"; PT-04): {@code 202} con la richiesta
 * {@code PENDING}; il portale interroga fino a uno stato finale o {@code CONFIRMED}. Identità del membro esplicita
 * ({@code memberId}, niente login nel PoC).
 */
@RestController
@RequestMapping("/v1/portal/redemptions")
public class PortalRedemptionsController {

    public record RedemptionRequest(String memberId, String rewardCode, JsonNode shipping) {
    }

    private final RedemptionService redemptions;

    public PortalRedemptionsController(RedemptionService redemptions) {
        this.redemptions = redemptions;
    }

    @PostMapping
    public ResponseEntity<RedemptionService.RequestResult> request(@RequestBody RedemptionRequest r) {
        if (r == null) {
            throw LhException.badRequest("corpo della richiesta mancante");
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(redemptions.request(r.memberId(), r.rewardCode(), r.shipping()));
    }

    @GetMapping
    public List<RedemptionService.RedemptionView> list(@RequestParam(required = false) String memberId) {
        if (memberId == null || memberId.isBlank()) {
            throw LhException.badRequest("memberId è obbligatorio");
        }
        return redemptions.byMember(memberId);
    }

    @GetMapping("/{id}")
    public RedemptionService.RedemptionView get(@PathVariable String id, @RequestParam(required = false) String memberId) {
        RedemptionService.RedemptionView v = redemptions.view(id);
        if (memberId != null && !memberId.isBlank() && !memberId.equals(v.memberId())) {
            throw LhException.notFound("Richiesta non trovata: " + id);
        }
        return v;
    }

    @PostMapping("/{id}/cancel")
    public RedemptionService.RedemptionView cancel(@PathVariable String id, @RequestParam(required = false) String memberId) {
        return redemptions.cancelByMember(id, memberId);
    }
}
