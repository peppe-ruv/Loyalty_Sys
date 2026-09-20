package io.loyaltyhub.insight.domain;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Una voce di audit (docs/servizi/insight-service.md §2, docs/05 §6): ricostruita dagli eventi
 * {@code io.loyaltyhub.audit.entry} su {@code lh.audit.v1}. {@code before}/{@code after} contengono
 * solo i campi cambiati; {@code actorRole}/{@code actorName} vengono dallo {@code lhactor}.
 */
public record AuditRecord(
        String id,
        String eventId,
        Instant at,
        String actorRole,
        String actorName,
        String service,
        String entityType,
        String entityId,
        String action,
        String summary,
        JsonNode before,
        JsonNode after,
        String correlationId) {
}
