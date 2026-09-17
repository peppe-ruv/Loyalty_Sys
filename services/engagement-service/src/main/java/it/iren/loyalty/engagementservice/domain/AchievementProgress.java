package it.iren.loyalty.engagementservice.domain;

import java.time.Instant;
import java.util.List;

/**
 * Stato di avanzamento di un membro su un achievement: eventi contati (istante, valore, valore unico), completamenti.
 * Immutabile: {@link AchievementEngine} restituisce una nuova istanza. Per gli streak conta i periodi consecutivi chiusi.
 */
public record AchievementProgress(String memberId, String achievementId, String version, List<Counted> events, int completedCount, Instant lastCompletedAt) {
    public record Counted(Instant at, double value, String unique) {}
    public static AchievementProgress empty(String memberId, Achievement a) { return new AchievementProgress(memberId, a.id(), a.version(), List.of(), 0, null); }
}
