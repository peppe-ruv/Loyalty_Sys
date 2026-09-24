package io.loyaltyhub.ingestion.domain;

/**
 * Tipo azione noto (docs/servizi/ingestion-service.md §2). {@code dataSchema} è il JSON Schema di {@code data};
 * {@code sampleData} il {@code data} d'esempio del simulatore. Entrambi come testo JSON.
 */
public record EventType(
        String code,
        String name,
        String description,
        String origin,
        String category,
        String dataSchema,
        String sampleData,
        boolean enabled,
        String icon
) {
    public static final String SYSTEM = "SYSTEM";
    public static final String CUSTOM = "CUSTOM";

    public boolean hasSchema() {
        return dataSchema != null && !dataSchema.isBlank();
    }

    public boolean isCustom() {
        return CUSTOM.equals(origin);
    }

    /** Chiave della cache degli schemi compilati: cambia quando un tipo custom cambia schema. */
    public String schemaCacheKey() {
        return code + "#" + (dataSchema == null ? 0 : dataSchema.hashCode());
    }
}
