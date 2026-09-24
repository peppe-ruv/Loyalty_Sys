package io.loyaltyhub.member.domain;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Segmento di membri (docs/servizi/member-service.md §2; docs/02 F-SEG-01/02): {@code STATIC} = elenco manuale,
 * {@code DYNAMIC} = criteri ricalcolati (docs/03 §10). {@code code} è immutabile: lo citano campagne, premi e contenuti.
 */
public record Segment(
        String id,
        String code,
        String name,
        String description,
        String type,
        JsonNode criteria,
        String status,
        int memberCount,
        Instant refreshedAt,
        int version,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy
) {
    public static final String STATIC = "STATIC";
    public static final String DYNAMIC = "DYNAMIC";
    public static final String ACTIVE = "ACTIVE";
    public static final String ARCHIVED = "ARCHIVED";

    public boolean isDynamic() {
        return DYNAMIC.equals(type);
    }

    public boolean isActive() {
        return ACTIVE.equals(status);
    }
}
