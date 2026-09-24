package io.loyaltyhub.common.approval;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Coda approvazioni nel formato comune (docs/06 §7, F-APR-03) e policy in sola lettura (scheda {@code policy} di
 * BO-21, F-APR-02). Registrato dall'auto-config solo nei processi con almeno una {@link ApprovalSource}.
 * SPEC-GAP: Q-96 — {@code submittedBy} («Inviate da me») e {@code /v1/approvals/policy} non sono nelle schede servizio.
 */
@RestController
@RequestMapping("/v1/approvals")
public class ApprovalsController {

    public record PolicyView(boolean enabled, long campaignBudgetThreshold, List<ApprovalPolicy.PolicyRow> rows) {
    }

    private final List<ApprovalSource> sources;
    private final ApprovalPolicy policy;

    public ApprovalsController(List<ApprovalSource> sources, ApprovalPolicy policy) {
        this.sources = sources;
        this.policy = policy;
    }

    @GetMapping
    public List<ApprovalItem> approvals(@RequestParam(required = false) String submittedBy) {
        List<ApprovalItem> out = new ArrayList<>();
        sources.forEach(s -> out.addAll(s.approvals(submittedBy)));
        return out;
    }

    @GetMapping("/policy")
    public PolicyView policy() {
        return new PolicyView(policy.enabled(), policy.campaignBudgetThreshold(), policy.rows());
    }
}
