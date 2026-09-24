package io.loyaltyhub.engagement.domain;

import java.time.Instant;

/**
 * Consegna di un fatto a un webhook (docs/servizi/engagement-service.md §2, §5): una riga per (webhook, evento), il
 * corpo firmato ({@code payload}, CloudEvent originale) e l'esito dell'ultimo tentativo. {@code maxAttempts} è la
 * costante della politica ({@link WebhookRetry#MAX_ATTEMPTS}), comoda per BO-23 ("tentativo 2 di 4").
 */
public record WebhookDelivery(String id, String webhookId, String eventId, String factType, String memberId, boolean test,
                              String payload, String signature, int attempt, int maxAttempts, String status,
                              Integer httpStatus, String responseExcerpt, String error, Integer durationMs,
                              Instant nextAttemptAt, Instant lastAttemptAt, Instant createdAt) {
}
