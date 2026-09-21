package io.loyaltyhub.wallet.domain;

/** Saldo di un wallet per valuta (docs/servizi/wallet-service.md §2). */
public record WalletBalance(
        String memberId,
        String currency,
        long balanceActive,
        long balancePending,
        long lifetimeEarned,
        long lifetimeSpent,
        long lifetimeExpired
) {
}
