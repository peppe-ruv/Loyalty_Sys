package io.loyaltyhub.insight.trace;

import java.time.Instant;
import java.util.List;

/**
 * Tracciato di un'azione (docs/servizi/insight-service.md §3): l'albero degli eventi con lo stesso
 * {@code correlationId}, i tempi relativi e l'esito sintetico. Ricostruito dall'event store.
 */
public record Trace(
        String correlationId,
        String memberId,
        Instant startedAt,
        long durationMs,
        String status,          // COMPLETE | IN_PROGRESS | FAILED
        List<TraceNode> nodes,
        Outcome outcome) {

    /** Un nodo dell'albero: {@code parentEventId} = {@code causationId} (null per la radice). */
    public record TraceNode(
            String eventId,
            String family,
            String shortType,
            String service,
            Instant time,
            long offsetMs,
            String parentEventId,
            String summary) {
    }

    /** Esito del tracciato (docs §3): punti per valuta, cambio tier, e conteggi di messaggi/coupon/giocate/DLQ. */
    public record Outcome(
            List<PointAmount> points,
            TierChange tierChange,
            int messages,
            int coupons,
            int plays,
            int dlq) {
    }

    public record PointAmount(String currency, long amount) {
    }

    public record TierChange(String from, String to) {
    }

    /** Riga di elenco dei tracciati (docs §3, {@code GET /v1/traces}). */
    public record TraceSummary(
            String correlationId,
            String memberId,
            String rootShortType,
            Instant startedAt,
            long durationMs,
            String status,
            String outcomeSummary) {
    }
}
