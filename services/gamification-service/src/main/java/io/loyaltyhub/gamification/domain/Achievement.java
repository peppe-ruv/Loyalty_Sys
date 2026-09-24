package io.loyaltyhub.gamification.domain;

import tools.jackson.databind.JsonNode;

import java.util.List;

/** Obiettivo (docs/servizi/gamification-service.md §2, docs/03 §8; F-ACH-01). */
public record Achievement(
        String id,
        String code,
        String name,
        String description,
        String icon,
        List<String> actionTypes,
        JsonNode filter,
        String metric,
        String sumField,
        String streakUnit,
        long target,
        String period,
        boolean repeatable,
        String badgeCode,
        String status
) {
    public static final List<String> METRICS = List.of("COUNT", "SUM", "DISTINCT_TYPES", "STREAK");
    public static final List<String> PERIODS = List.of("EVER", "MONTH", "EDITION");
    public static final List<String> STREAK_UNITS = List.of("DAY", "WEEK");
}
