package io.loyaltyhub.insight.live;

import java.time.Instant;

/**
 * Proiezione "leggera" di un evento per lo stream live (docs/servizi/insight-service.md §3): ciò che il rail
 * mostra senza il payload completo. La {@code summary} è generata per tipo ({@link EventSummaries}).
 */
public record LiveEvent(
        String eventId,
        String topic,
        String family,
        String shortType,
        String memberId,
        String correlationId,
        Instant time,
        String summary) {
}
