package io.loyaltyhub.common.approval;

import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.Role;
import org.junit.jupiter.api.Test;

import static io.loyaltyhub.common.approval.ApprovalAction.APPROVE;
import static io.loyaltyhub.common.approval.ApprovalAction.PUBLISH;
import static io.loyaltyhub.common.approval.ApprovalAction.REJECT;
import static io.loyaltyhub.common.approval.ApprovalAction.SUBMIT;
import static io.loyaltyhub.common.approval.ApprovalStatus.APPROVED;
import static io.loyaltyhub.common.approval.ApprovalStatus.DRAFT;
import static io.loyaltyhub.common.approval.ApprovalStatus.IN_REVIEW;
import static io.loyaltyhub.common.approval.ApprovalStatus.LIVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Transizioni per ruolo e policy (docs/03 §3.6, docs/06 §7, docs/12 §M7 accettazione). */
class GovernedTransitionsTest {

    private final ApprovalPolicy on = new ApprovalPolicy(true, 100_000);
    private final ApprovalPolicy off = new ApprovalPolicy(false, 100_000);

    @Test
    void marketingCannotPublishAContestButCanSubmitIt() {
        ApprovalRule contest = on.forContest();
        assertThatThrownBy(() -> GovernedTransitions.next(DRAFT, PUBLISH, contest, true, Role.MARKETING, null))
                .isInstanceOf(LhException.class).hasMessageContaining("richiede approvazione");
        assertThat(GovernedTransitions.next(DRAFT, SUBMIT, contest, true, Role.MARKETING, null)).isEqualTo(IN_REVIEW);
    }

    @Test
    void onlyThePolicyRoleOrAdminDecides() {
        ApprovalRule contest = on.forContest();
        assertThatThrownBy(() -> GovernedTransitions.next(IN_REVIEW, APPROVE, contest, true, Role.MARKETING, "ok"))
                .isInstanceOf(LhException.class).extracting("code").isEqualTo("FORBIDDEN_ROLE");
        assertThat(GovernedTransitions.next(IN_REVIEW, APPROVE, contest, true, Role.LEGAL, "ok")).isEqualTo(APPROVED);
        assertThat(GovernedTransitions.next(IN_REVIEW, APPROVE, contest, true, Role.ADMIN, null)).isEqualTo(APPROVED);
        assertThat(GovernedTransitions.isOverride(APPROVE, contest, Role.ADMIN)).isTrue();
        assertThat(GovernedTransitions.isOverride(APPROVE, contest, Role.LEGAL)).isFalse();
        assertThat(GovernedTransitions.next(APPROVED, PUBLISH, contest, true, Role.MARKETING, null)).isEqualTo(LIVE);
        assertThatThrownBy(() -> GovernedTransitions.next(APPROVED, PUBLISH, contest, true, Role.LEGAL, null))
                .extracting("code").isEqualTo("FORBIDDEN_ROLE");
    }

    @Test
    void rejectWithoutCommentIs422AndReturnsToDraft() {
        ApprovalRule reward = on.forReward();
        assertThatThrownBy(() -> GovernedTransitions.next(IN_REVIEW, REJECT, reward, true, Role.LEGAL, "  "))
                .extracting("code").isEqualTo("REJECT_COMMENT_REQUIRED");
        assertThat(GovernedTransitions.next(IN_REVIEW, REJECT, reward, true, Role.LEGAL, "termini mancanti")).isEqualTo(DRAFT);
    }

    @Test
    void campaignsNeedLegalOnlyAboveBudgetOrWhenMarked() {
        assertThat(on.forCampaign(false, 50_000L).required()).isFalse();
        assertThat(on.forCampaign(false, null).required()).isFalse();
        assertThat(on.forCampaign(false, 100_001L).approverRole()).isEqualTo(Role.LEGAL);
        assertThat(on.forCampaign(true, null).required()).isTrue();
        assertThat(GovernedTransitions.next(DRAFT, PUBLISH, on.forCampaign(false, 1_000L), true, Role.MARKETING, null))
                .isEqualTo(LIVE);
    }

    @Test
    void withApprovalOffEverythingPublishesDirectly() {
        assertThat(off.forContest().required()).isFalse();
        assertThat(GovernedTransitions.next(DRAFT, SUBMIT, off.forReward(), false, Role.MARKETING, null)).isEqualTo(LIVE);
        assertThat(GovernedTransitions.next(DRAFT, PUBLISH, off.forContest(), false, Role.MARKETING, null)).isEqualTo(LIVE);
    }
}
