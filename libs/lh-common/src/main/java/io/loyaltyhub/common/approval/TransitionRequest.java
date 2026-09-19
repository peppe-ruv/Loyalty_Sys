package io.loyaltyhub.common.approval;

/** Corpo di {@code POST /v1/<risorsa>/{id}/transitions} (docs/06 §2, §7). */
public record TransitionRequest(ApprovalAction action, String comment) {

    public boolean hasComment() {
        return comment != null && !comment.isBlank();
    }
}
