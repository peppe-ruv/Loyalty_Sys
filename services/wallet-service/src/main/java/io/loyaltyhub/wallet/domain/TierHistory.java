package io.loyaltyhub.wallet.domain;

import java.time.Instant;

/** Voce dello storico livelli (docs/servizi/wallet-service.md §2). {@code kind}: UPGRADE|DOWNGRADE|RETAIN|INITIAL. */
public record TierHistory(
        String id,
        String memberId,
        String fromTier,
        String toTier,
        String kind,
        String editionCode,
        Instant at
) {
    public static final String UPGRADE = "UPGRADE";
    public static final String DOWNGRADE = "DOWNGRADE";
    public static final String RETAIN = "RETAIN";
    public static final String INITIAL = "INITIAL";
}
