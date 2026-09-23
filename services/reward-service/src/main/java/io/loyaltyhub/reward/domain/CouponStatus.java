package io.loyaltyhub.reward.domain;

/** Stati di un codice coupon (docs/servizi/reward-service.md §2). */
public enum CouponStatus {
    AVAILABLE, ISSUED, USED, EXPIRED, VOID
}
