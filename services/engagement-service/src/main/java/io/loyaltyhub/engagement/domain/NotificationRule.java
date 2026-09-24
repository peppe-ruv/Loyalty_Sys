package io.loyaltyhub.engagement.domain;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Regola di notifica (docs/03 §9, docs/servizi/engagement-service.md §2; F-MSG-01): un tipo di fatto (forma breve,
 * es. {@code wallet.points.earned}) più una condizione opzionale su {@code data.*} porta a un template.
 */
public record NotificationRule(String id, String code, String factType, JsonNode condition, String templateCode,
                               boolean enabled, long version, Instant updatedAt, String updatedBy) {
}
