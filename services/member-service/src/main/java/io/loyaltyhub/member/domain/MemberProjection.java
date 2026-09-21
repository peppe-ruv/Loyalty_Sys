package io.loyaltyhub.member.domain;

import java.time.Instant;

/** Proiezione di saldi/tier riflessa dal wallet (docs/servizi/member-service.md §2, §5). */
public record MemberProjection(
        String memberId,
        String tierCode,
        long periodSts,
        long balancePts,
        long pendingPts,
        long lifetimeEarnedPts,
        Instant updatedAt
) {
    public static MemberProjection base(String memberId) {
        return new MemberProjection(memberId, "BASE", 0, 0, 0, 0, null);
    }
}
