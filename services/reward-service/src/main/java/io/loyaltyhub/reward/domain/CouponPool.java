package io.loyaltyhub.reward.domain;

import java.time.Instant;

/** Pool di codici coupon (docs/servizi/reward-service.md §2; F-CPN-01). Codici {@code prefix-XXXX-XXXX}. */
public record CouponPool(String id, String code, String name, String prefix, int validityDays, long seed, Instant createdAt) {
}
