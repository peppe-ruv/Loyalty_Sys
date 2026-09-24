package io.loyaltyhub.member.api;

import io.loyaltyhub.member.domain.MemberAttributes;
import tools.jackson.databind.JsonNode;
import io.loyaltyhub.member.domain.Member;
import io.loyaltyhub.member.domain.MemberProjection;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Vista di un membro per il backoffice (docs §3): colonne di {@code member} + la proiezione saldi/tier. */
public record MemberView(
        String id,
        String externalId,
        String firstName,
        String lastName,
        String nickname,
        String email,
        String phone,
        LocalDate birthDate,
        String gender,
        String city,
        String status,
        String channel,
        Instant registeredAt,
        String referralCode,
        String referredBy,
        List<String> labels,
        JsonNode attributes,
        boolean profileCompleted,
        long version,
        String tier,
        long balancePts,
        long pendingPts,
        long periodSts,
        long lifetimeEarnedPts
) {
    public static MemberView of(Member m, MemberProjection p) {
        MemberProjection proj = p != null ? p : MemberProjection.base(m.id());
        return new MemberView(
                m.id(), m.externalId(), m.firstName(), m.lastName(), m.nickname(), m.email(), m.phone(),
                m.birthDate(), m.gender(), m.city(), m.status().name(), m.channel(), m.registeredAt(),
                m.referralCode(), m.referredBy(), m.labels(), MemberAttributes.visible(MemberAttributes.parse(m.attributesJson())),
                m.profileCompletedAt() != null, m.version(),
                proj.tierCode(), proj.balancePts(), proj.pendingPts(), proj.periodSts(), proj.lifetimeEarnedPts());
    }
}
