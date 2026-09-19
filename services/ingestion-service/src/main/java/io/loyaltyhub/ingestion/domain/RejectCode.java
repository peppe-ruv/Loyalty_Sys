package io.loyaltyhub.ingestion.domain;

/** Codici di rifiuto della pipeline di accettazione (docs/servizi/ingestion-service.md §5). */
public enum RejectCode {
    SOURCE_DISABLED,
    UNKNOWN_TYPE,
    TYPE_NOT_ALLOWED,
    INVALID_DATA,
    INVALID_TIME,
    MEMBER_NOT_ACTIVE
}
