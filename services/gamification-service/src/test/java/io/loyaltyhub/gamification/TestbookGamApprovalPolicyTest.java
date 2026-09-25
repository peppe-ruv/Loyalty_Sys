package io.loyaltyhub.gamification;

import io.loyaltyhub.common.approval.ApprovalAction;
import io.loyaltyhub.common.approval.ApprovalPolicy;
import io.loyaltyhub.common.approval.ApprovalRule;
import io.loyaltyhub.common.approval.ApprovalStatus;
import io.loyaltyhub.common.approval.GovernedTransitions;
import io.loyaltyhub.common.web.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-GAM — policy di approvazione dei concorsi ({@code APR}) (docs/testbook/TB-GAM-gioco.md §7): la regola
 * {@code CONTEST} che usa {@code ContestAdminService#transition}, con approvazione accesa (da M7) e spenta (fino a M7).
 * Oracolo: docs/06 §7 (CONTEST: sempre, LEGAL; {@code enabled=false} consente DRAFT → LIVE), docs/08 §2 (override ADMIN).
 */
class TestbookGamApprovalPolicyTest {

    @Test
    @DisplayName("[TB-GAM-APR-001] approvazione accesa: i concorsi richiedono sempre LEGAL")
    void contestRequiresLegal() {
        ApprovalRule rule = new ApprovalPolicy(true, 100_000).forContest();
        assertThat(rule.required()).isTrue();
        assertThat(rule.approverRole()).isEqualTo(Role.LEGAL);
    }

    @Test
    @DisplayName("[TB-GAM-APR-002] approvazione spenta: PUBLISH da DRAFT porta direttamente a LIVE")
    void disabledPublishFromDraft() {
        ApprovalPolicy off = new ApprovalPolicy(false, 100_000);
        assertThat(GovernedTransitions.next(ApprovalStatus.DRAFT, ApprovalAction.PUBLISH, off.forContest(), off.enabled(),
                Role.MARKETING, null)).isEqualTo(ApprovalStatus.LIVE);
    }

    // Q-282: con approvazione spenta anche SUBMIT pubblica direttamente (comune a campagne, premi e concorsi).
    @Test
    @DisplayName("[TB-GAM-APR-003] approvazione spenta: SUBMIT da DRAFT pubblica direttamente")
    void disabledSubmitPublishes() {
        ApprovalPolicy off = new ApprovalPolicy(false, 100_000);
        assertThat(GovernedTransitions.next(ApprovalStatus.DRAFT, ApprovalAction.SUBMIT, off.forContest(), off.enabled(),
                Role.MARKETING, null)).isEqualTo(ApprovalStatus.LIVE);
    }

    @Test
    @DisplayName("[TB-GAM-APR-004] APPROVE di ADMIN è un override da marcare in audit; quello di LEGAL no")
    void adminOverride() {
        ApprovalRule rule = new ApprovalPolicy(true, 100_000).forContest();
        assertThat(GovernedTransitions.isOverride(ApprovalAction.APPROVE, rule, Role.ADMIN)).isTrue();
        assertThat(GovernedTransitions.isOverride(ApprovalAction.APPROVE, rule, Role.LEGAL)).isFalse();
    }
}
