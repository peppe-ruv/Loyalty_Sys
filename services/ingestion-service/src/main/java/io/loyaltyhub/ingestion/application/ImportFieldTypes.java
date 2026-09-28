package io.loyaltyhub.ingestion.application;

import io.loyaltyhub.common.event.LhFamily;
import io.loyaltyhub.ingestion.domain.ImportParser;
import io.loyaltyhub.ingestion.domain.SchemaFields;
import io.loyaltyhub.ingestion.infra.EventTypeRepository;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Tipi dei campi di primo livello di {@code data} dallo schema del tipo azione (registro {@code event_type}), per le
 * colonne {@code data.<campo>} dell'import CSV (F2-ING-02). Tipo sconosciuto o schema assente → nessun tipo (testo).
 */
@Component
public class ImportFieldTypes implements ImportParser.FieldTypes {

    private final EventTypeRepository eventTypes;
    private final ObjectMapper mapper;

    public ImportFieldTypes(EventTypeRepository eventTypes, ObjectMapper mapper) {
        this.eventTypes = eventTypes;
        this.mapper = mapper;
    }

    @Override
    public Map<String, String> of(String type) {
        if (type == null || type.isBlank()) {
            return Map.of();
        }
        String prefix = LhFamily.ACTION.typePrefix();
        String shortType = type.startsWith(prefix) ? type.substring(prefix.length()) : type;
        return eventTypes.findByCode(shortType)
                .filter(t -> t.hasSchema())
                .map(t -> {
                    try {
                        return SchemaFields.topLevelTypes(mapper.readTree(t.dataSchema()));
                    } catch (JacksonException e) {
                        return Map.<String, String>of();
                    }
                })
                .orElse(Map.of());
    }
}
