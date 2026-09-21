package io.loyaltyhub.member.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** Statistiche di attività di un membro (docs/servizi/member-service.md §2). */
public record MemberStats(
        String memberId,
        Instant lastActivityAt,
        long actionsTotal,
        String actionsByTypeJson,
        long purchasesCount,
        BigDecimal purchasesAmount90d,
        BigDecimal purchasesAmountTotal
) {
}
