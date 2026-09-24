package io.loyaltyhub.common.approval;

import io.loyaltyhub.common.web.Role;

import java.util.List;

/**
 * Policy delle approvazioni (docs/06 §7, F-APR-02), configurata da {@code loyaltyhub.approval.*}:
 * <table>
 *   <tr><td>CONTEST</td><td>sempre</td><td>LEGAL</td></tr>
 *   <tr><td>REWARD</td><td>sempre</td><td>LEGAL</td></tr>
 *   <tr><td>CAMPAIGN</td><td>se {@code requiresLegal} o budget &gt; soglia (100 000 punti)</td><td>LEGAL</td></tr>
 *   <tr><td>CONTENT</td><td>mai</td><td>—</td></tr>
 * </table>
 * Con {@code enabled=false} nessun oggetto richiede approvazione (DRAFT → LIVE diretto, come prima di M7).
 */
public class ApprovalPolicy {

    public static final String CAMPAIGN = "CAMPAIGN";
    public static final String REWARD = "REWARD";
    public static final String CONTEST = "CONTEST";
    public static final String CONTENT = "CONTENT";

    /** Riga della scheda {@code policy} di BO-21. */
    public record PolicyRow(String entityType, String when, Role approverRole) {
    }

    private final boolean enabled;
    private final long campaignBudgetThreshold;

    public ApprovalPolicy(boolean enabled, long campaignBudgetThreshold) {
        this.enabled = enabled;
        this.campaignBudgetThreshold = campaignBudgetThreshold;
    }

    public boolean enabled() {
        return enabled;
    }

    public long campaignBudgetThreshold() {
        return campaignBudgetThreshold;
    }

    public ApprovalRule forContest() {
        return enabled ? ApprovalRule.legal("i concorsi si approvano sempre") : ApprovalRule.NONE;
    }

    public ApprovalRule forReward() {
        return enabled ? ApprovalRule.legal("i premi si approvano sempre") : ApprovalRule.NONE;
    }

    /** @param budgetPoints tetto di punti della campagna ({@code limits.global.maxPoints}), {@code null} se assente */
    public ApprovalRule forCampaign(boolean requiresLegal, Long budgetPoints) {
        if (!enabled) {
            return ApprovalRule.NONE;
        }
        if (requiresLegal) {
            return ApprovalRule.legal("campagna segnata «richiede LEGAL»");
        }
        if (budgetPoints != null && budgetPoints > campaignBudgetThreshold) {
            return ApprovalRule.legal("budget oltre " + campaignBudgetThreshold + " punti");
        }
        return ApprovalRule.NONE;
    }

    public List<PolicyRow> rows() {
        return List.of(
                new PolicyRow(CONTEST, enabled ? "sempre" : "mai (approvazione spenta)", enabled ? Role.LEGAL : null),
                new PolicyRow(REWARD, enabled ? "sempre" : "mai (approvazione spenta)", enabled ? Role.LEGAL : null),
                new PolicyRow(CAMPAIGN, enabled ? "se «richiede LEGAL» o budget oltre " + campaignBudgetThreshold + " punti"
                        : "mai (approvazione spenta)", enabled ? Role.LEGAL : null),
                new PolicyRow(CONTENT, "mai (pubblicazione diretta)", null));
    }
}
