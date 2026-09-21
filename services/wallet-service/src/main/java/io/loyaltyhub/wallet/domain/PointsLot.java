package io.loyaltyhub.wallet.domain;

import java.time.Instant;

/**
 * Lotto di punti (docs/03 §4.2, docs/servizi/wallet-service.md §2). Ogni {@code EARN}/{@code ADJUST_CREDIT}/
 * {@code REFUND} crea un lotto. {@code pendingDays > 0} ⇒ {@code PENDING} fino a {@code availableAt}, poi
 * {@code ACTIVE} (rilascio); scade a {@code expiresAt} secondo la policy della valuta. Il saldo attivo è la
 * somma dei {@code remaining} dei lotti {@code ACTIVE}, quello in attesa dei {@code PENDING}.
 */
public record PointsLot(
        String id,
        String memberId,
        String currency,
        long amount,
        long remaining,
        String status,        // PENDING | ACTIVE | EXHAUSTED | EXPIRED
        Instant earnedAt,
        Instant availableAt,  // quando il lotto diventa ACTIVE (null = subito)
        Instant expiresAt,    // null = non scade
        String ledgerEntryId) {

    public static final String PENDING = "PENDING";
    public static final String ACTIVE = "ACTIVE";
    public static final String EXHAUSTED = "EXHAUSTED";
    public static final String EXPIRED = "EXPIRED";
}
