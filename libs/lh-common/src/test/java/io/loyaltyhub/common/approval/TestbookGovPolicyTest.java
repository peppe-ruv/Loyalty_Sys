package io.loyaltyhub.common.approval;

import io.loyaltyhub.common.web.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestbookGovPolicyTest {

    @Test
    @DisplayName("[TB-GOV-025] Policy CONTEST, policy=on -> required=true, LEGAL")
    void policyContestOn() {
        ApprovalPolicy policy = new ApprovalPolicy(true, 100_000);
        ApprovalRule rule = policy.forContest();
        assertThat(rule.required()).isTrue();
        assertThat(rule.approverRole()).isEqualTo(Role.LEGAL);
    }

    @Test
    @DisplayName("[TB-GOV-026] Policy REWARD, policy=on -> required=true, LEGAL")
    void policyRewardOn() {
        ApprovalPolicy policy = new ApprovalPolicy(true, 100_000);
        ApprovalRule rule = policy.forReward();
        assertThat(rule.required()).isTrue();
        assertThat(rule.approverRole()).isEqualTo(Role.LEGAL);
    }

    @Test
    @DisplayName("[TB-GOV-027] Policy CONTENT, policy=on -> required=false")
    void policyContentOn() {
        // CONTENT is handled directly returning ApprovalRule.NONE or implicitly handled via rows()
        ApprovalPolicy policy = new ApprovalPolicy(true, 100_000);
        ApprovalPolicy.PolicyRow contentRow = policy.rows().stream().filter(r -> r.entityType().equals(ApprovalPolicy.CONTENT)).findFirst().get();
        assertThat(contentRow.approverRole()).isNull();
    }

    @Test
    @DisplayName("[TB-GOV-028] Policy CAMPAIGN, policy=on, reqLegal=true -> required=true, LEGAL")
    void policyCampaignReqLegalOn() {
        ApprovalPolicy policy = new ApprovalPolicy(true, 100_000);
        ApprovalRule rule = policy.forCampaign(true, 0L);
        assertThat(rule.required()).isTrue();
        assertThat(rule.approverRole()).isEqualTo(Role.LEGAL);
    }

    @Test
    @DisplayName("[TB-GOV-029] Policy CAMPAIGN, policy=on, reqLegal=false, budget=100000 -> required=false")
    void policyCampaignNoReqLegalBudgetOkOn() {
        ApprovalPolicy policy = new ApprovalPolicy(true, 100_000);
        ApprovalRule rule = policy.forCampaign(false, 100_000L);
        assertThat(rule.required()).isFalse();
    }

    @Test
    @DisplayName("[TB-GOV-030] Policy CAMPAIGN, policy=on, reqLegal=false, budget=100001 -> required=true, LEGAL")
    void policyCampaignNoReqLegalBudgetExceededOn() {
        ApprovalPolicy policy = new ApprovalPolicy(true, 100_000);
        ApprovalRule rule = policy.forCampaign(false, 100_001L);
        assertThat(rule.required()).isTrue();
        assertThat(rule.approverRole()).isEqualTo(Role.LEGAL);
    }

    @Test
    @DisplayName("[TB-GOV-031] Policy CONTEST, policy=off -> required=false")
    void policyContestOff() {
        ApprovalPolicy policy = new ApprovalPolicy(false, 100_000);
        ApprovalRule rule = policy.forContest();
        assertThat(rule.required()).isFalse();
    }
}
