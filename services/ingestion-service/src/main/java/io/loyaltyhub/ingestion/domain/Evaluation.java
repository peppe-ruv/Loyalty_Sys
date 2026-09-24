package io.loyaltyhub.ingestion.domain;

import io.loyaltyhub.common.event.LhEvent;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Esito dei passi 2–8 della pipeline di accettazione (docs/servizi/ingestion-service.md §5) <em>senza</em>
 * persistenza: lo usa l'ingresso normale (che poi inserisce la riga) e la rivalutazione di una riga già salvata
 * (<em>Riprova</em> / <em>Abbina</em>, F-ING-04, F-ING-09), che invece aggiorna la riga esistente.
 *
 * @param event envelope da salvare come {@code payload}: per {@code ACCEPTED} è l'azione arricchita da pubblicare
 *              (subject normalizzato a {@code member:<id>}); negli altri casi (anche {@code DUPLICATE}, che si
 *              salva come riga del monitor ma non si pubblica) l'envelope col subject originale.
 */
public record Evaluation(
        InboundStatus status,
        RejectCode rejectCode,
        String detail,
        String memberId,
        String eventId,
        String sourceCode,
        String typeCode,
        String subject,
        Instant time,
        String correlationId,
        LhEvent<JsonNode> event
) {
    public boolean accepted() {
        return status == InboundStatus.ACCEPTED;
    }

    public IngestResult toResult() {
        return switch (status) {
            case ACCEPTED -> IngestResult.accepted(eventId, memberId, correlationId);
            case DUPLICATE -> IngestResult.duplicate(eventId, memberId, correlationId);
            case UNMATCHED -> IngestResult.unmatched(eventId, correlationId);
            case REJECTED -> IngestResult.rejected(eventId, memberId, correlationId, rejectCode, detail);
        };
    }
}
