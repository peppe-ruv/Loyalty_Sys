package io.loyaltyhub.common.testbook;

import io.loyaltyhub.common.approval.ApprovalPolicy;
import io.loyaltyhub.common.approval.ApprovalRule;
import io.loyaltyhub.common.web.Role;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-GOV §7 — policy delle approvazioni (docs/06 §7, F-APR-02, Q-08): tipo di oggetto × policy accesa/spenta ×
 * {@code requiresLegal} × budget ai limiti della soglia (100 000 punti, o la soglia configurata); scheda della policy di
 * BO-21 ({@code /v1/approvals/policy}, Q-96).
 */
class TestbookGovPolicyTest {

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gov/policy.csv", numLinesToSkip = 1)
    void rule(String id, String description, boolean enabled, long threshold, String entity, boolean requiresLegal,
              String budget, boolean required, String approver) {
        ApprovalPolicy policy = new ApprovalPolicy(enabled, threshold);
        ApprovalRule r = switch (entity) {
            case "CAMPAIGN" -> policy.forCampaign(requiresLegal, budget(budget));
            case "CONTEST" -> policy.forContest();
            case "REWARD" -> policy.forReward();
            // CONTENT: la policy non ha un metodo (i contenuti si pubblicano sempre direttamente): vale la sua riga.
            default -> policy.rows().stream().filter(p -> p.entityType().equals(entity)).findFirst()
                    .map(p -> p.approverRole() == null ? ApprovalRule.NONE : ApprovalRule.legal(p.when()))
                    .orElseThrow();
        };
        assertThat(r.required()).as("%s: richiesta", id).isEqualTo(required);
        assertThat(r.approverRole() == null ? "NONE" : r.approverRole().name()).as("%s: approvatore", id)
                .isEqualTo(approver);
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gov/policy-rows.csv", numLinesToSkip = 1)
    void rows(String id, String description, boolean enabled, String entity, String approver) {
        ApprovalPolicy policy = new ApprovalPolicy(enabled, 100_000);
        ApprovalPolicy.PolicyRow row = policy.rows().stream().filter(p -> p.entityType().equals(entity)).findFirst()
                .orElseThrow(() -> new AssertionError(id + ": manca la riga " + entity));
        Role role = row.approverRole();
        assertThat(role == null ? "NONE" : role.name()).as("%s: approvatore", id).isEqualTo(approver);
        assertThat(row.when()).as("%s: regola leggibile", id).isNotBlank();
    }

    private static Long budget(String raw) {
        return switch (raw) {
            case "NULL" -> null;
            case "MAX" -> Long.MAX_VALUE;
            default -> Long.valueOf(raw);
        };
    }
}
