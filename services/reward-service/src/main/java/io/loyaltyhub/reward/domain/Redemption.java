package io.loyaltyhub.reward.domain;

import java.time.Instant;

/** Richiesta premio (docs/servizi/reward-service.md §2; F-RWD-05). {@code shipping} è il JSON dell'indirizzo. */
public record Redemption(
        String id,
        String memberId,
        String rewardCode,
        String rewardName,
        long pointsCost,
        RedemptionStatus status,
        String rejectReason,
        boolean needsAttention,
        String couponCode,
        String fulfilmentNote,
        String shippingJson,
        String correlationId,
        Instant requestedAt,
        Instant confirmedAt,
        Instant closedAt,
        String actor
) {
}
