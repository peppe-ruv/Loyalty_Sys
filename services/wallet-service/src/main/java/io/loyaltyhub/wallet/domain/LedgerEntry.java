package io.loyaltyhub.wallet.domain;

import java.time.Instant;

/** Riga del libro mastro (docs/servizi/wallet-service.md §2). {@code amount} è sempre positivo. */
public record LedgerEntry(
        String id,
        String memberId,
        String currency,
        String type,
        long amount,
        String direction,
        long balanceAfter,
        Instant occurredAt,
        String sourceType,
        String campaignCode,
        String description,
        String metadataJson
) {
}
