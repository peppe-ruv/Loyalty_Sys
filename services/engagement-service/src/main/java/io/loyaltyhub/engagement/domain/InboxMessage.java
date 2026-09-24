package io.loyaltyhub.engagement.domain;

import java.time.Instant;

/** Messaggio consegnato (docs/servizi/engagement-service.md §2): già reso, con l'evento sorgente per la deduplica. */
public record InboxMessage(String id, String memberId, String templateCode, String channel, String title, String body,
                           String icon, String linkTarget, String category, String sourceEventId, String sourceType,
                           String correlationId, Instant createdAt, Instant readAt) {
}
