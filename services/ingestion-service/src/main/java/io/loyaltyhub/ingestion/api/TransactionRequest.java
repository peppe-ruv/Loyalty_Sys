package io.loyaltyhub.ingestion.api;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;

/**
 * Corpo di {@code POST /v1/transactions} (docs/servizi/ingestion-service.md §3, F-ING-07):
 * {@code {source, orderId, memberRef, amount, currency, channel, items[], occurredAt}}.
 * {@code memberRef} usa le stesse forme del {@code subject} ({@code member:<id>}, {@code external:<x>}, {@code email:<x>}).
 * {@code kind} ({@code PURCHASE} predefinito | {@code RETURN}) è un'estensione opzionale per il reso (SPEC-GAP Q-49).
 */
public record TransactionRequest(
        String source,
        String orderId,
        String memberRef,
        BigDecimal amount,
        String currency,
        String channel,
        JsonNode items,
        String occurredAt,
        String kind
) {
}
