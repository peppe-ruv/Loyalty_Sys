package io.loyaltyhub.decisionservice.api;

import io.loyaltyhub.common.event.RewardingAction;
import io.loyaltyhub.decisionservice.app.DecisionLog;
import io.loyaltyhub.decisionservice.app.DecisionService;
import io.loyaltyhub.decisionservice.domain.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * API del motore decisionale (RF-127..RF-130):
 * <ul>
 *   <li>{@code POST /v1/decisions/next-best-action/{memberId}} — getNextBestAction(customerId, context) → {customerId, action, offerId, channel, reason, expiresAt, metadata}</li>
 *   <li>{@code GET /v1/decisions/{id}} e {@code GET /v1/decisions/members/{memberId}} — decision log spiegabile</li>
 *   <li>{@code POST /v1/decisions/simulate} — simulazione con policy/offerte in bozza, senza effetti</li>
 *   <li>{@code POST /v1/decisions/predictions/{memberId}} — previsioni correnti del membro (per il backoffice)</li>
 *   <li>{@code GET /v1/decisions/policy} — policy attiva risolta (per verifica dal backoffice)</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/decisions")
public class DecisionController {
    private final DecisionService decisions;
    private final DecisionLog log;
    private final Ports.PolicySource policies;
    private final Ports.ContextSource contexts;
    private final PredictionProvider predictions;

    public DecisionController(DecisionService decisions, DecisionLog log, Ports.PolicySource policies, Ports.ContextSource contexts, PredictionProvider predictions) {
        this.decisions = decisions; this.log = log; this.policies = policies; this.contexts = contexts; this.predictions = predictions;
    }

    public record NbaRequest(Map<String, Object> context, Boolean persist) {}
    public record NbaResponse(String customerId, String action, String offerId, String channel, String reason, String expiresAt, Map<String, Object> metadata) {}

    @PostMapping("/next-best-action/{memberId}")
    public NbaResponse nextBestAction(@PathVariable String memberId, @RequestBody(required = false) NbaRequest r) {
        boolean persist = r == null || r.persist() == null || r.persist();
        Decision d = decisions.nextBestAction(memberId, r == null ? Map.of() : r.context(), persist);
        var primary = d.actions().isEmpty() ? null : d.actions().get(0);
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("decisionId", d.decisionId());
        meta.put("policyVersion", d.policyVersion());
        meta.put("experimentId", d.experimentId());
        meta.put("variant", d.variant());
        meta.put("score", primary == null ? null : primary.score());
        meta.put("predictions", d.predictions());
        meta.put("rejected", d.rejected());
        if (primary != null && primary.params() != null) meta.put("params", primary.params());
        return new NbaResponse(memberId, d.primaryAction(), primary == null ? null : primary.reference(), primary == null ? null : primary.channel(),
                primary == null ? "NO_ACTION: " + d.rejected().stream().map(Decision.Rejected::reasonCode).distinct().toList() : String.join("; ", primary.reasons()),
                d.expiresAt().toString(), meta);
    }

    @GetMapping("/{decisionId}")
    public ResponseEntity<Decision> get(@PathVariable String decisionId) { return ResponseEntity.of(log.find(decisionId)); }

    @GetMapping("/members/{memberId}")
    public List<Decision> byMember(@PathVariable String memberId, @RequestParam(defaultValue = "20") int limit) { return log.byMember(memberId, Math.min(limit, 200)); }

    public record SimulationRequest(String memberId, DecisionContext context, RewardingAction action, DecisionPolicy policy, List<Offer> offers, List<Candidate> candidates) {}

    @PostMapping("/simulate")
    public Decision simulate(@RequestBody SimulationRequest r) {
        String memberId = r.memberId() != null ? r.memberId() : r.context() != null ? r.context().memberId() : "simulated";
        return decisions.simulate(memberId, r.context(), r.action(), r.policy(), r.offers(), r.candidates());
    }

    @PostMapping("/predictions/{memberId}")
    public Map<String, Double> predictions(@PathVariable String memberId) { return predictions.predict(contexts.load(memberId), java.util.Set.of()); }

    @GetMapping("/policy")
    public DecisionPolicy policy() { return policies.current(); }

    @GetMapping("/stats")
    public List<Map<String, Object>> stats() { return log.stats24h(); }

    /**
     * Effetto per variante di un esperimento (RF-134): decisioni, membri distinti, azioni e unità concesse. È il
     * confronto onesto fra varianti che si può fare dal decision log; l'uplift sul comportamento (riscatti, spesa)
     * si misura nel warehouse, che quei dati li ha.
     */
    @GetMapping("/experiments/{experimentId}/effect")
    public Map<String, Object> experimentEffect(@PathVariable String experimentId) {
        return Map.of("experimentId", experimentId, "variants", log.experimentEffect(experimentId));
    }
}
