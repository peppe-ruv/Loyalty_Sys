package io.loyaltyhub.reward.domain;

import java.time.Instant;

/** Un codice coupon con il suo stato (docs/servizi/reward-service.md §2; F-CPN-01..03). */
public record Coupon(
        String code,
        String poolId,
        CouponStatus status,
        String memberId,
        String rewardCode,
        String origin,
        String redemptionId,
        String effectId,
        Instant issuedAt,
        Instant expiresAt,
        Instant usedAt,
        Instant voidedAt
) {
    /** Emesso ma oltre la scadenza a {@code now}: si tratta come scaduto anche prima del job giornaliero. */
    public boolean expiredAt(Instant now) {
        return status == CouponStatus.EXPIRED || (status == CouponStatus.ISSUED && expiresAt != null && !now.isBefore(expiresAt));
    }
}
