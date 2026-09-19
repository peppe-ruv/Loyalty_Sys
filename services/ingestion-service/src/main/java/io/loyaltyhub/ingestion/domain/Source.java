package io.loyaltyhub.ingestion.domain;

import java.util.List;

/** Fonte che invia azioni (docs/servizi/ingestion-service.md §2). {@code allowedTypes} vuoto = tutti. */
public record Source(String code, String name, String kind, boolean enabled, List<String> allowedTypes, String description) {

    public boolean allows(String typeCode) {
        return allowedTypes.isEmpty() || allowedTypes.contains(typeCode);
    }
}
