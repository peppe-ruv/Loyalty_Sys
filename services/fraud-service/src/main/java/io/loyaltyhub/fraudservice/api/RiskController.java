package io.loyaltyhub.fraudservice.api;

import io.loyaltyhub.fraudservice.app.RiskService;
import io.loyaltyhub.fraudservice.app.RiskStore;
import io.loyaltyhub.fraudservice.domain.RiskEngine;
import io.loyaltyhub.fraudservice.domain.RiskPolicy;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * API di rischio (RF-131): valutazione corrente e storico del membro, rivalutazione a richiesta, simulazione con
 * policy in bozza dal backoffice, lista dei membri a rischio per la console operatore.
 */
@RestController
@RequestMapping("/v1/risk")
public class RiskController {
    private final RiskStore store;
    private final RiskService risk;
    private final Supplier<RiskPolicy> policy;

    public RiskController(RiskStore store, RiskService risk, Supplier<RiskPolicy> policy) { this.store = store; this.risk = risk; this.policy = policy; }

    @GetMapping("/members/{memberId}")
    public ResponseEntity<Map<String, Object>> current(@PathVariable String memberId) {
        return store.current(memberId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.ok(Map.of("memberId", memberId, "score", 0, "level", "LOW", "reasons", "[]", "blocked", false)));
    }

    @GetMapping("/members/{memberId}/history")
    public List<Map<String, Object>> history(@PathVariable String memberId) { return store.history(memberId); }

    @PostMapping("/members/{memberId}/assess")
    public RiskEngine.Assessment assess(@PathVariable String memberId) { return risk.assess(memberId, "manual"); }

    public record SimulationRequest(RiskEngine.MemberActivity activity, RiskPolicy policy) {}

    @PostMapping("/simulate")
    public RiskEngine.Assessment simulate(@RequestBody SimulationRequest r) { return risk.simulate(r.activity(), r.policy()); }

    @GetMapping("/policy")
    public RiskPolicy policy() { return policy.get(); }

    @GetMapping("/top")
    public List<Map<String, Object>> top(@RequestParam(defaultValue = "50") int limit) { return store.topRisk(Math.min(limit, 500)); }
}
