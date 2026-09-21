package io.loyaltyhub.ingestion.domain;

/** Risposta di {@code POST /v1/events} (docs/servizi/ingestion-service.md §3): {@code 202 {eventId, status, memberId?, rejectCode?}}. */
public record IngestResult(
        String eventId,
        InboundStatus status,
        String memberId,
        String correlationId,
        RejectCode rejectCode,
        String detail
) {
    public static IngestResult accepted(String eventId, String memberId, String correlationId) {
        return new IngestResult(eventId, InboundStatus.ACCEPTED, memberId, correlationId, null, null);
    }

    public static IngestResult duplicate(String eventId, String memberId, String correlationId) {
        return new IngestResult(eventId, InboundStatus.DUPLICATE, memberId, correlationId, null, null);
    }

    public static IngestResult unmatched(String eventId, String correlationId) {
        return new IngestResult(eventId, InboundStatus.UNMATCHED, null, correlationId, null, null);
    }

    public static IngestResult rejected(String eventId, String memberId, String correlationId, RejectCode code, String detail) {
        return new IngestResult(eventId, InboundStatus.REJECTED, memberId, correlationId, code, detail);
    }
}
