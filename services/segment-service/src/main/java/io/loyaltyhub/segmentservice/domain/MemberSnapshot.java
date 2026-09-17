package io.loyaltyhub.segmentservice.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Vista del membro usata dai criteri: profilo (adesione, tier, etichette, consensi, saldo) e riassunto delle azioni.
 * È costruita dal read-model (CQRS, ADR-010); il valutatore non interroga altri servizi.
 */
public record MemberSnapshot(
        String memberId,
        Instant enrolledAt,
        String tier,
        Map<String, String> labels,
        Map<String, Boolean> consents,
        long premioAvailable,
        List<ActionSummary> actions,
        /** campi custom (RF-99), badge (RF-92), completamenti di achievement/challenge/campagne (RF-90, RF-91, RF-86) */
        Map<String, Object> customFields,
        List<String> badges,
        Map<String, Integer> achievementsCompleted,
        Map<String, Double> achievementProgress,
        Map<String, Integer> challengesCompleted,
        List<Completion> campaignCompletions
) {
    public MemberSnapshot(String memberId, Instant enrolledAt, String tier, Map<String, String> labels, Map<String, Boolean> consents, long premioAvailable, List<ActionSummary> actions) {
        this(memberId, enrolledAt, tier, labels, consents, premioAvailable, actions, Map.of(), List.of(), Map.of(), Map.of(), Map.of(), List.of());
    }
    public record Completion(String campaignId, Instant at) {}
    /** Riassunto di un'azione premiante: tipo, quando, importo, canale e righe (RF-62). */
    public record ActionSummary(String actionType, Instant occurredAt, BigDecimal amountEur, String channel, List<Line> lines) {
        public BigDecimal amountOrZero() { return amountEur == null ? BigDecimal.ZERO : amountEur; }
    }
    public record Line(String sku, String brand, String category, List<String> labels) {}
}
