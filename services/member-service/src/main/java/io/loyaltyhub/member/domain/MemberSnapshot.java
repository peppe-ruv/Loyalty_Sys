package io.loyaltyhub.member.domain;

import java.time.Instant;
import java.util.List;

/**
 * Snapshot completo del membro trasportato dai fatti {@code member.registered}/{@code member.updated}
 * (docs/servizi/member-service.md §5): gli altri servizi sovrascrivono il proprio snapshot, nessun merge.
 */
public record MemberSnapshot(
        String memberId,
        String externalId,
        String firstName,
        String lastName,
        String nickname,
        String email,
        String phone,
        String city,
        String gender,
        String status,
        String channel,
        String tier,
        Instant registeredAt,
        String referralCode,
        String referredBy,
        List<String> labels,
        boolean profileCompleted
) {
    public static MemberSnapshot of(Member m, String tier) {
        return new MemberSnapshot(
                m.id(), m.externalId(), m.firstName(), m.lastName(), m.nickname(), m.email(), m.phone(),
                m.city(), m.gender(), m.status().name(), m.channel(), tier, m.registeredAt(),
                m.referralCode(), m.referredBy(), m.labels(), m.profileCompletedAt() != null);
    }
}
