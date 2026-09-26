package io.loyaltyhub.common.event;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.dialect.DialectId;
import com.networknt.schema.path.PathType;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validatore JSON Schema 2020-12 (docs/05 §9, docs/06 §1). Usa networknt 3.x, che lavora su Jackson 3 come il resto
 * del sistema (Q-43); accetta schema e dati come <em>stringhe JSON</em>. Gli schemi sono compilati e messi in cache
 * per {@code cacheKey}.
 * <p>
 * I messaggi restano nella forma della 1.x: {@code <percorso>: <messaggio>} con percorso {@code $.campo}
 * ({@link PathType#LEGACY}); {@code format} resta un'annotazione (asserzioni non attivate) e le parole chiave
 * sconosciute ({@code x-lh-*}) sono ignorate.
 */
public final class JsonSchemaValidator {

    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaRegistryConfig(SchemaRegistryConfig.builder().pathType(PathType.LEGACY).build()));
    /** Come il mapper Jackson 2 usato con la 1.x: nessun errore su token dopo il documento. */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final ConcurrentHashMap<String, Schema> cache = new ConcurrentHashMap<>();

    /**
     * Valida {@code dataJson} contro {@code schemaJson}. Ritorna i messaggi d'errore (vuoto = valido).
     *
     * @param cacheKey chiave stabile dello schema (es. il codice del tipo azione) per la cache
     */
    public List<String> validate(String cacheKey, String schemaJson, String dataJson) {
        Schema schema = cache.computeIfAbsent(cacheKey, k -> compile(schemaJson));
        try {
            return messages(schema.validate(MAPPER.readTree(dataJson)));
        } catch (Exception e) {
            return List.of("data non è JSON valido: " + e.getMessage());
        }
    }

    /**
     * Verifica che {@code schemaJson} sia un JSON Schema 2020-12 valido contro il meta-schema ufficiale (incluso nella
     * libreria, nessun accesso di rete). Ritorna i messaggi d'errore (vuoto = schema valido).
     */
    public List<String> metaSchemaErrors(String schemaJson) {
        try {
            Schema meta = cache.computeIfAbsent(META_SCHEMA_KEY,
                    k -> REGISTRY.getSchema(SchemaLocation.of(DialectId.DRAFT_2020_12)));
            return messages(meta.validate(MAPPER.readTree(schemaJson)));
        } catch (Exception e) {
            return List.of("schema non è JSON valido: " + e.getMessage());
        }
    }

    private static final String META_SCHEMA_KEY = "urn:loyaltyhub:meta-schema:2020-12";

    /** {@code <percorso>: <messaggio>} come la 1.x, ordinati. */
    private static List<String> messages(List<Error> errors) {
        return errors.stream().map(Error::toString).sorted().toList();
    }

    private Schema compile(String schemaJson) {
        try {
            return REGISTRY.getSchema(MAPPER.readTree(schemaJson));
        } catch (Exception e) {
            throw new IllegalArgumentException("Schema JSON non valido", e);
        }
    }
}
