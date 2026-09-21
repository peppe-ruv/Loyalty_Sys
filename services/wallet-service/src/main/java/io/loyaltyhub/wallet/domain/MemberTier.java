package io.loyaltyhub.wallet.domain;

import java.time.Instant;

/** Livello corrente di un membro (docs/servizi/wallet-service.md §2). */
public record MemberTier(
        String memberId,
        String tierCode,
        Instant since,
        long periodSts,
        String previousTier,
        String memberStatus
) {
}
