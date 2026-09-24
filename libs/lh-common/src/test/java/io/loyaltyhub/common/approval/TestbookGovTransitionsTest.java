package io.loyaltyhub.common.approval;

import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestbookGovTransitionsTest {

    @Test
    @DisplayName("[TB-GOV-001] DRAFT + SUBMIT -> IN_REVIEW")
    void draftSubmit() {
        assertThat(ApprovalStateMachine.next(ApprovalStatus.DRAFT, ApprovalAction.SUBMIT, true, false))
                .isEqualTo(ApprovalStatus.IN_REVIEW);
    }

    @Test
    @DisplayName("[TB-GOV-002] IN_REVIEW + APPROVE -> APPROVED")
    void inReviewApprove() {
        assertThat(ApprovalStateMachine.next(ApprovalStatus.IN_REVIEW, ApprovalAction.APPROVE, true, false))
                .isEqualTo(ApprovalStatus.APPROVED);
    }

    @Test
    @DisplayName("[TB-GOV-003] IN_REVIEW + REJECT + commento -> DRAFT")
    void inReviewRejectWithComment() {
        assertThat(ApprovalStateMachine.next(ApprovalStatus.IN_REVIEW, ApprovalAction.REJECT, true, true))
                .isEqualTo(ApprovalStatus.DRAFT);
    }

    @Test
    @DisplayName("[TB-GOV-004] APPROVED + PUBLISH -> LIVE")
    void approvedPublish() {
        assertThat(ApprovalStateMachine.next(ApprovalStatus.APPROVED, ApprovalAction.PUBLISH, true, false))
                .isEqualTo(ApprovalStatus.LIVE);
    }

    @Test
    @DisplayName("[TB-GOV-005] LIVE + PAUSE -> PAUSED")
    void livePause() {
        assertThat(ApprovalStateMachine.next(ApprovalStatus.LIVE, ApprovalAction.PAUSE, true, false))
                .isEqualTo(ApprovalStatus.PAUSED);
    }

    @Test
    @DisplayName("[TB-GOV-006] PAUSED + RESUME -> LIVE")
    void pausedResume() {
        assertThat(ApprovalStateMachine.next(ApprovalStatus.PAUSED, ApprovalAction.RESUME, true, false))
                .isEqualTo(ApprovalStatus.LIVE);
    }

    @Test
    @DisplayName("[TB-GOV-007] LIVE + END -> ENDED")
    void liveEnd() {
        assertThat(ApprovalStateMachine.next(ApprovalStatus.LIVE, ApprovalAction.END, true, false))
                .isEqualTo(ApprovalStatus.ENDED);
    }

    @Test
    @DisplayName("[TB-GOV-008] PAUSED + END -> ENDED")
    void pausedEnd() {
        assertThat(ApprovalStateMachine.next(ApprovalStatus.PAUSED, ApprovalAction.END, true, false))
                .isEqualTo(ApprovalStatus.ENDED);
    }

    @Test
    @DisplayName("[TB-GOV-009] ENDED + ARCHIVE -> ARCHIVED")
    void endedArchive() {
        assertThat(ApprovalStateMachine.next(ApprovalStatus.ENDED, ApprovalAction.ARCHIVE, true, false))
                .isEqualTo(ApprovalStatus.ARCHIVED);
    }

    @Test
    @DisplayName("[TB-GOV-010] DRAFT + ARCHIVE -> ARCHIVED")
    void draftArchive() {
        assertThat(ApprovalStateMachine.next(ApprovalStatus.DRAFT, ApprovalAction.ARCHIVE, true, false))
                .isEqualTo(ApprovalStatus.ARCHIVED);
    }

    @Test
    @DisplayName("[TB-GOV-011] LIVE + SUBMIT -> 409 INVALID_TRANSITION")
    void liveSubmitInvalid() {
        assertThatThrownBy(() -> ApprovalStateMachine.next(ApprovalStatus.LIVE, ApprovalAction.SUBMIT, true, false))
                .isInstanceOf(LhException.class)
                .hasMessageContaining("non ammessa dallo stato LIVE");
    }

    @Test
    @DisplayName("[TB-GOV-012] IN_REVIEW + REJECT + no comment -> 422 REJECT_COMMENT_REQUIRED")
    void inReviewRejectNoComment() {
        assertThatThrownBy(() -> ApprovalStateMachine.next(ApprovalStatus.IN_REVIEW, ApprovalAction.REJECT, true, false))
                .isInstanceOf(LhException.class)
                .hasMessageContaining("rifiuto richiede un commento");
    }

    @Test
    @DisplayName("[TB-GOV-013] DRAFT + PUBLISH, policy=on, rule=req -> 409 APPROVAL_REQUIRED")
    void draftPublishRequiredApproval() {
        assertThatThrownBy(() -> ApprovalStateMachine.next(ApprovalStatus.DRAFT, ApprovalAction.PUBLISH, true, false))
                .isInstanceOf(LhException.class)
                .hasMessageContaining("richiede approvazione prima della pubblicazione");
    }

    @Test
    @DisplayName("[TB-GOV-014] DRAFT + PUBLISH, policy=on, rule=not_req -> LIVE")
    void draftPublishNotRequiredApproval() {
        assertThat(ApprovalStateMachine.next(ApprovalStatus.DRAFT, ApprovalAction.PUBLISH, false, false))
                .isEqualTo(ApprovalStatus.LIVE);
    }

    @Test
    @DisplayName("[TB-GOV-015] DRAFT + SUBMIT, policy=off -> LIVE")
    void draftSubmitPolicyOff() {
        assertThat(GovernedTransitions.next(ApprovalStatus.DRAFT, ApprovalAction.SUBMIT, ApprovalRule.legal(""), false, Role.MARKETING, ""))
                .isEqualTo(ApprovalStatus.LIVE);
    }

    @Test
    @DisplayName("[TB-GOV-016] DRAFT + PUBLISH, policy=off -> LIVE")
    void draftPublishPolicyOff() {
        assertThat(GovernedTransitions.next(ApprovalStatus.DRAFT, ApprovalAction.PUBLISH, ApprovalRule.NONE, false, Role.MARKETING, ""))
                .isEqualTo(ApprovalStatus.LIVE);
    }

    @Test
    @DisplayName("[TB-GOV-017] Azione APPROVE con ruolo LEGAL -> APPROVED, not override")
    void approveLegalRole() {
        ApprovalRule rule = ApprovalRule.legal("");
        assertThat(GovernedTransitions.next(ApprovalStatus.IN_REVIEW, ApprovalAction.APPROVE, rule, true, Role.LEGAL, ""))
                .isEqualTo(ApprovalStatus.APPROVED);
        assertThat(GovernedTransitions.isOverride(ApprovalAction.APPROVE, rule, Role.LEGAL)).isFalse();
    }

    @Test
    @DisplayName("[TB-GOV-018] Azione APPROVE con ruolo ADMIN -> APPROVED, is override")
    void approveAdminRole() {
        ApprovalRule rule = ApprovalRule.legal("");
        assertThat(GovernedTransitions.next(ApprovalStatus.IN_REVIEW, ApprovalAction.APPROVE, rule, true, Role.ADMIN, ""))
                .isEqualTo(ApprovalStatus.APPROVED);
        assertThat(GovernedTransitions.isOverride(ApprovalAction.APPROVE, rule, Role.ADMIN)).isTrue();
    }

    @Test
    @DisplayName("[TB-GOV-019] Azione APPROVE con ruolo MARKETING -> 403 ForbiddenRole")
    void approveMarketingRole() {
        ApprovalRule rule = ApprovalRule.legal("");
        assertThatThrownBy(() -> GovernedTransitions.next(ApprovalStatus.IN_REVIEW, ApprovalAction.APPROVE, rule, true, Role.MARKETING, ""))
                .isInstanceOf(LhException.class)
                .hasMessageContaining("Approvare o respingere richiede il ruolo");
    }

    @Test
    @DisplayName("[TB-GOV-020] Azione SUBMIT con ruolo MARKETING -> IN_REVIEW")
    void submitMarketingRole() {
        ApprovalRule rule = ApprovalRule.legal("");
        assertThat(GovernedTransitions.next(ApprovalStatus.DRAFT, ApprovalAction.SUBMIT, rule, true, Role.MARKETING, ""))
                .isEqualTo(ApprovalStatus.IN_REVIEW);
    }

    @Test
    @DisplayName("[TB-GOV-021] Azione SUBMIT con ruolo LEGAL -> 403 ForbiddenRole")
    void submitLegalRole() {
        ApprovalRule rule = ApprovalRule.legal("");
        assertThatThrownBy(() -> GovernedTransitions.next(ApprovalStatus.DRAFT, ApprovalAction.SUBMIT, rule, true, Role.LEGAL, ""))
                .isInstanceOf(LhException.class)
                .hasMessageContaining("richiede il ruolo MARKETING o ADMIN");
    }

    @Test
    @DisplayName("[TB-GOV-022] Azione SUBMIT con ruolo ADMIN -> IN_REVIEW")
    void submitAdminRole() {
        ApprovalRule rule = ApprovalRule.legal("");
        assertThat(GovernedTransitions.next(ApprovalStatus.DRAFT, ApprovalAction.SUBMIT, rule, true, Role.ADMIN, ""))
                .isEqualTo(ApprovalStatus.IN_REVIEW);
    }

    @Test
    @DisplayName("[TB-GOV-023] Azione SUBMIT con ruolo CARE -> 403 ForbiddenRole")
    void submitCareRole() {
        ApprovalRule rule = ApprovalRule.legal("");
        assertThatThrownBy(() -> GovernedTransitions.next(ApprovalStatus.DRAFT, ApprovalAction.SUBMIT, rule, true, Role.CARE, ""))
                .isInstanceOf(LhException.class)
                .hasMessageContaining("richiede il ruolo MARKETING o ADMIN");
    }

    @Test
    @DisplayName("[TB-GOV-024] Azione SUBMIT con ruolo ANALYST -> 403 ForbiddenRole")
    void submitAnalystRole() {
        ApprovalRule rule = ApprovalRule.legal("");
        assertThatThrownBy(() -> GovernedTransitions.next(ApprovalStatus.DRAFT, ApprovalAction.SUBMIT, rule, true, Role.ANALYST, ""))
                .isInstanceOf(LhException.class)
                .hasMessageContaining("richiede il ruolo MARKETING o ADMIN");
    }
}
