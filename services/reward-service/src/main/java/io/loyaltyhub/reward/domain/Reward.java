package io.loyaltyhub.reward.domain;

import java.time.Instant;
import java.util.List;

/**
 * Premio del catalogo (docs/servizi/reward-service.md §2). {@code stockTotal == null} = illimitato; il costo non sta
 * qui ma nella fascia ({@code bandCode}).
 */
public record Reward(
        String id,
        String code,
        String name,
        String description,
        String terms,
        String imageUrl,
        String type,
        String categoryCode,
        String bandCode,
        String fulfilment,
        String couponPoolId,
        Integer stockTotal,
        Integer stockRemaining,
        Integer perMemberLimit,
        List<String> eligibleTiers,
        List<String> eligibleSegments,
        Instant validFrom,
        Instant validTo,
        RewardStatus status,
        long version,
        String createdBy,
        Instant updatedAt
) {
    public static final List<String> TYPES = List.of("PHYSICAL", "COUPON", "DIGITAL", "DONATION", "EXPERIENCE");
    public static final List<String> FULFILMENTS = List.of("AUTO_COUPON", "MANUAL", "INSTANT");

    public boolean unlimited() {
        return stockTotal == null;
    }

    /** Dentro il periodo di validità a {@code now} (estremi opzionali). */
    public boolean validAt(Instant now) {
        return (validFrom == null || !now.isBefore(validFrom)) && (validTo == null || now.isBefore(validTo));
    }
}
