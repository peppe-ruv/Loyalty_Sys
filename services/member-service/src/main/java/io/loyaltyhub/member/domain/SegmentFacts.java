package io.loyaltyhub.member.domain;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Tutto ciò che un criterio di segmento può leggere di un membro (docs/03 §10): lo spazio {@code member.*} delle
 * condizioni (§3.3) esteso con saldo, punti guadagnati, ultima attività, conteggi per tipo azione e spesa. Le finestre
 * ({@code count30d}, {@code amount90d}) sono già calcolate rispetto alla data di riferimento del ricalcolo.
 */
public record SegmentFacts(
        String memberId,
        String displayName,
        String status,
        String tier,
        List<String> labels,
        JsonNode attributes,
        Instant registeredAt,
        LocalDate birthDate,
        String city,
        long balancePts,
        long lifetimeEarnedPts,
        Instant lastActivityAt,
        Map<String, ActionWindow> actions,
        double purchasesAmount90d
) {
    /** Contatori di un tipo azione: ultimi 30 giorni (rispetto ad {@code asOf}) e totale storico. */
    public record ActionWindow(long count30d, long total) {
    }
}
