package io.loyaltyhub.member.domain;

import java.util.List;

/**
 * Attributo personalizzato del membro (docs/servizi/member-service.md §2, F-MBR-03): {@code key} usata come
 * {@code member.attributes.<key>} in condizioni e segmenti; {@code options} solo per {@code STRING} (enum).
 */
public record AttributeDefinition(String key, String label, String type, List<String> options) {

    public static final List<String> TYPES = List.of("STRING", "NUMBER", "BOOLEAN", "DATE");

    public AttributeDefinition {
        options = options == null ? List.of() : List.copyOf(options);
    }
}
