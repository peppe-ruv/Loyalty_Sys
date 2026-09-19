package io.loyaltyhub.wallet.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** Vista del wallet (docs/servizi/wallet-service.md §3): saldi per valuta + livello con avanzamento. */
public record WalletView(String memberId, Map<String, Balance> balances, TierView tier) {

    public record Balance(long active, long pending, long lifetimeEarned, long lifetimeSpent) {
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
