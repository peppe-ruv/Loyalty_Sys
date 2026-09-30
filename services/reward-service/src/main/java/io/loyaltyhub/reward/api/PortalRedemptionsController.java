package io.loyaltyhub.reward.api;

import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.MemberEndpoint;
import io.loyaltyhub.common.web.MemberPrincipal;
import io.loyaltyhub.reward.application.RedemptionService;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Richieste premio dal portale (docs/servizi/reward-service.md §3 "Portale"; PT-04): {@code 202} con la richiesta
 * {@code PENDING}; il portale interroga fino a uno stato finale o {@code CONFIRMED}. Il membro non è un parametro: lo
 * risolve {@code EndpointAccessInterceptor} dal token (profilo {@code enterprise}) o da {@code X-LH-Member} /
 * {@code memberId} (profilo {@code demo}, regola 6-bis) e lo consegna come {@link MemberPrincipal} (Q-410, ADR-048,
 * docs/06 §3.4). Una richiesta di un altro membro dà {@code 404} (il suo esistere non si rivela).
 */
@RestController
@RequestMapping("/v1/portal/redemptions")
public class PortalRedemptionsController {

    /**
     * Corpo della richiesta premio. {@code memberId} è deprecato e vale solo in {@code demo}: in {@code enterprise} un
     * valore non nullo, anche il proprio, dà {@code 400 MEMBER_FROM_TOKEN} (il membro è quello del token).
     */
    public record RedemptionRequest(
            @Schema(deprecated = true, description = "solo profilo demo; in enterprise il membro è quello del token")
            String memberId,
            String rewardCode, JsonNode shipping) {
    }

    private final RedemptionService redemptions;

    public PortalRedemptionsController(RedemptionService redemptions) {
        this.redemptions = redemptions;
    }

    @PostMapping
    @MemberEndpoint
    public ResponseEntity<RedemptionService.RequestResult> request(@RequestBody RedemptionRequest r, MemberPrincipal principal) {
        if (r == null) {
            throw LhException.badRequest("corpo della richiesta mancante");
        }
        // SPEC-GAP: Q-573 — a memberId spelling that RedemptionRequest does not bind (e.g. MEMBER_ID) is dropped by Jackson,
        // not refused; the member still comes only from the token (merge + MemberBodyAdvice reject the bound field).
        String memberId = principal.merge(r.memberId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(redemptions.request(memberId, r.rewardCode(), r.shipping()));
    }

    @GetMapping
    @MemberEndpoint
    public List<RedemptionService.RedemptionView> list(MemberPrincipal principal) {
        return redemptions.byMember(requireMember(principal));
    }

    @GetMapping("/{id}")
    @MemberEndpoint
    public RedemptionService.RedemptionView get(@PathVariable String id, MemberPrincipal principal) {
        RedemptionService.RedemptionView v = redemptions.view(id);
        // Il proprietario si controlla sempre in enterprise; in demo solo se il chiamante indica un membro (come prima).
        String me = principal.idOrNull();
        if (me != null && !me.isBlank()) {
            principal.checkOwner(v.memberId(), "Richiesta non trovata: " + id);
        }
        return v;
    }

    @PostMapping("/{id}/cancel")
    @MemberEndpoint
    public RedemptionService.RedemptionView cancel(@PathVariable String id, MemberPrincipal principal) {
        // Q-283 DECISA: identità del portale (CLAUDE.md §1.6); una richiesta altrui dà 404 (cancelByMember).
        return redemptions.cancelByMember(id, requireMember(principal));
    }

    /**
     * Il membro obbligatorio. In {@code demo} l'assenza (o un valore vuoto) dà lo stesso {@code 400} di prima; in
     * {@code enterprise} il membro c'è sempre (un handler {@code REQUIRED} senza token di membro non arriva qui).
     */
    private static String requireMember(MemberPrincipal principal) {
        String memberId = principal.idOrNull();
        if (memberId == null || memberId.isBlank()) {
            throw LhException.badRequest("memberId è obbligatorio");
        }
        return memberId;
    }
}
