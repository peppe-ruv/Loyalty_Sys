package io.loyaltyhub.common.approval;

import io.loyaltyhub.common.web.LhException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalStateMachineTest {

    @Test
    void draftToReviewToApprovedToLive() {
        assertThat(ApprovalStateMachine.next(ApprovalStatus.DRAFT, ApprovalAction.SUBMIT, true, false))
                .isEqualTo(ApprovalStatus.IN_REVIEW);
        assertThat(ApprovalStateMachine.next(ApprovalStatus.IN_REVIEW, ApprovalAction.APPROVE, true, false))
                .isEqualTo(ApprovalStatus.APPROVED);
        assertThat(ApprovalStateMachine.next(ApprovalStatus.APPROVED, ApprovalAction.PUBLISH, true, false))
                .isEqualTo(ApprovalStatus.LIVE);
    }

    @Test
    void directPublishOnlyWhenApprovalNotRequired() {
        assertThat(ApprovalStateMachine.next(ApprovalStatus.DRAFT, ApprovalAction.PUBLISH, false, false))
                .isEqualTo(ApprovalStatus.LIVE);
        assertThatThrownBy(() -> ApprovalStateMachine.next(ApprovalStatus.DRAFT, ApprovalAction.PUBLISH, true, false))
                .isInstanceOf(LhException.class)
                .extracting(e -> ((LhException) e).status())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void rejectRequiresComment() {
        assertThatThrownBy(() -> ApprovalStateMachine.next(ApprovalStatus.IN_REVIEW, ApprovalAction.REJECT, true, false))
                .isInstanceOf(LhException.class)
                .extracting(e -> ((LhException) e).code())
                .isEqualTo("REJECT_COMMENT_REQUIRED");
        assertThat(ApprovalStateMachine.next(ApprovalStatus.IN_REVIEW, ApprovalAction.REJECT, true, true))
                .isEqualTo(ApprovalStatus.DRAFT);
    }

    @Test
    void pauseResumeEndArchive() {
        assertThat(ApprovalStateMachine.next(ApprovalStatus.LIVE, ApprovalAction.PAUSE, false, false))
                .isEqualTo(ApprovalStatus.PAUSED);
        assertThat(ApprovalStateMachine.next(ApprovalStatus.PAUSED, ApprovalAction.RESUME, false, false))
                .isEqualTo(ApprovalStatus.LIVE);
        assertThat(ApprovalStateMachine.next(ApprovalStatus.PAUSED, ApprovalAction.END, false, false))
                .isEqualTo(ApprovalStatus.ENDED);
        assertThat(ApprovalStateMachine.next(ApprovalStatus.ENDED, ApprovalAction.ARCHIVE, false, false))
                .isEqualTo(ApprovalStatus.ARCHIVED);
    }

    @Test
    void invalidTransitionIsConflict() {
        assertThatThrownBy(() -> ApprovalStateMachine.next(ApprovalStatus.DRAFT, ApprovalAction.PAUSE, false, false))
                .isInstanceOf(LhException.class)
                .extracting(e -> ((LhException) e).code())
                .isEqualTo("INVALID_TRANSITION");
    }
}
