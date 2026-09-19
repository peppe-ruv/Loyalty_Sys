package io.loyaltyhub.ingestion.domain;

/** Esito dell'accettazione di un evento in ingresso (docs/servizi/ingestion-service.md §2). */
public enum InboundStatus {
    ACCEPTED,
    DUPLICATE,
    REJECTED,
    UNMATCHED
}
