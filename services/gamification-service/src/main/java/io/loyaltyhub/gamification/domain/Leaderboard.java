package io.loyaltyhub.gamification.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** Classifica (docs/servizi/gamification-service.md §2, docs/03 §8; F-LDB-01). */
public record Leaderboard(
        String id,
        String code,
        String name,
        String metric,
        List<String> actionTypes,
        String period,
        int topN,
        String status
) {
    public static final List<String> METRICS = List.of("PTS_EARNED", "STS_EARNED", "ACTION_COUNT");
    public static final List<String> PERIODS = List.of("MONTH", "EDITION", "ALL_TIME");
    private static final ZoneId ZONE = ZoneId.of("Europe/Rome");

    /** Chiave del periodo: {@code 2026-09}, {@code ED-2026} (anno solare, SPEC-GAP Q-59), {@code ALL}. */
    // SPEC-GAP: Q-59
    public static String periodKey(String period, Instant at) {
        LocalDate d = LocalDate.ofInstant(at, ZONE);
        return switch (period) {
            case "MONTH" -> String.format("%d-%02d", d.getYear(), d.getMonthValue());
            case "EDITION" -> "ED-" + d.getYear();
            default -> "ALL";
        };
    }

    /** Valuta dei punti che la classifica osserva, {@code null} per il conteggio di azioni. */
    public String currency() {
        return switch (metric) {
            case "PTS_EARNED" -> "PTS";
            case "STS_EARNED" -> "STS";
            default -> null;
        };
    }
}
