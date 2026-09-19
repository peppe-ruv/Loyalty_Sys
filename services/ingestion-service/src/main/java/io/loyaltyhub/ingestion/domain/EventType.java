package io.loyaltyhub.ingestion.domain;

/** Tipo azione noto (docs/servizi/ingestion-service.md §2). {@code dataSchema} è il JSON Schema di {@code data}. */
public record EventType(
        String code,
        String name,
        String origin,
        String category,
        String dataSchema,
        boolean enabled,
        String icon
) {
    public boolean hasSchema() {
        return dataSchema != null && !dataSchema.isBlank();
    }
}
