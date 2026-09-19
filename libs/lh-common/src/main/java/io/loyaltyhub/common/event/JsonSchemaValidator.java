package io.loyaltyhub.common.event;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validatore JSON Schema 2020-12 (docs/05 §9, docs/06 §1). Usa networknt (Jackson 2) internamente:
 * accetta schema e dati come <em>stringhe JSON</em>, così il resto del sistema resta su Jackson 3 (Q-43).
 * Gli schemi sono compilati e messi in cache per {@code cacheKey}.
 */
public final class JsonSchemaValidator {

    private static final JsonSchemaFactory FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final ConcurrentHashMap<String, JsonSchema> cache = new ConcurrentHashMap<>();

    /**
     * Valida {@code dataJson} contro {@code schemaJson}. Ritorna i messaggi d'errore (vuoto = valido).
     *
     * @param cacheKey chiave stabile dello schema (es. il codice del tipo azione) per la cache
     */
    public List<String> validate(String cacheKey, String schemaJson, String dataJson) {
        JsonSchema schema = cache.computeIfAbsent(cacheKey, k -> compile(schemaJson));
        try {
            var errors = schema.validate(MAPPER.readTree(dataJson));
            return errors.stream().map(ValidationMessage::getMessage).sorted().toList();
        } catch (Exception e) {
            return List.of("data non è JSON valido: " + e.getMessage());
        }
    }

    private JsonSchema compile(String schemaJson) {
        try {
            return FACTORY.getSchema(MAPPER.readTree(schemaJson));
        } catch (Exception e) {
            throw new IllegalArgumentException("Schema JSON non valido", e);
        }
    }
}
