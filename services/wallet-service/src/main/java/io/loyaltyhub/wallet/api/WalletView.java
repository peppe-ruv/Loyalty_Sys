package io.loyaltyhub.wallet.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** Vista del wallet (docs/servizi/wallet-service.md §3): saldi per valuta + scadenze imminenti + livello. */
public record WalletView(String memberId, Map<String, Balance> balances, ExpiringSoon expiringSoon, TierView tier) {

    public record Balance(long active, long pending, long lifetimeEarned, long lifetimeSpent) {
    }

    /** Punti spendibili in scadenza entro 30 giorni (docs §3): {@code amount}, {@code within30d}, prima scadenza. */
    public record ExpiringSoon(long amount, boolean within30d, Instant nextExpiryAt) {
    }

    public record TierView(
            String code,
            String name,
            Instant since,
            long periodSts,
            BigDecimal multiplier,
            NextTier next,
            int progressPct
    ) {
    }

    public record NextTier(String code, long threshold, long missing) {
    }
}
