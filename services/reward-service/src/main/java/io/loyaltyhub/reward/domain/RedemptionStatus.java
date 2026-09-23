package io.loyaltyhub.reward.domain;

/** Stati di una richiesta premio (docs/03 §5). {@code REJECTED} e {@code CANCELLED} ripristinano lo stock. */
public enum RedemptionStatus {
    PENDING, CONFIRMED, FULFILLED, REJECTED, CANCELLED;

    public boolean isFinal() {
        return this == FULFILLED || this == REJECTED || this == CANCELLED;
    }
}
