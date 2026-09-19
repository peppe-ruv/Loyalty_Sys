package io.loyaltyhub.ingestion.domain;

/** Risposta di {@code POST /v1/events} (docs/servizi/ingestion-service.md §3). */
public record IngestResult(String eventId, InboundStatus status, String memberId, String correlationId) {
}
