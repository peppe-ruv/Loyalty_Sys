package io.loyaltyhub.gamification.domain;

/** Premio in palio (docs/servizi/gamification-service.md §2; F-IW-02): un istante vincente per unità. */
public record Prize(
        String id,
        String contestId,
        String code,
        String name,
        String type,
        Long points,
        String rewardCode,
        int quantityTotal,
        int quantityRemaining,
        String imageUrl,
        String wheelColor,
        int sortOrder
) {
    public static final java.util.List<String> TYPES = java.util.List.of("POINTS", "COUPON", "PHYSICAL");
}
