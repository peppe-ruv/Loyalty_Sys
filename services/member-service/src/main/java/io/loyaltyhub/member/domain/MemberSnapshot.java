package io.loyaltyhub.member.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * Snapshot completo del membro trasportato dai fatti {@code member.registered}/{@code member.updated}
 * (docs/servizi/member-service.md §5): gli altri servizi sovrascrivono il proprio snapshot, nessun merge.
 * {@code birthDate} e {@code attributes} (campi già previsti dal contratto EVT-FACT-01/02) servono a {@code member.age}
 * e {@code member.attributes.<k>} nelle condizioni delle campagne (M6.7). {@code subjectRef} è opzionale (EVT-FACT-01,
 * ADR-048): lo pseudonimo del legame account↔membro.
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
        boolean profileCompleted,
        String birthDate,
        JsonNode attributes,
        @JsonInclude(JsonInclude.Include.NON_NULL) String subjectRef
) {
    /** Snapshot di un membro senza legame con un account (seed, import, registrazione dal backoffice). */
    public static MemberSnapshot of(Member m, String tier) {
        return of(m, tier, null);
    }

    /**
     * Snapshot con il pseudonimo del legame (Q-552): {@code subjectRef} è l'HMAC di {@code (iss, sub)}, mai il {@code sub}.
     * {@code null} = campo assente sul bus = «legame invariato» per i consumer (un membro senza legame, o anonimizzato,
     * non lo porta). Ogni {@code member.updated} rinfresca così il legame nelle proiezioni dei servizi.
     */
    public static MemberSnapshot of(Member m, String tier, String subjectRef) {
        return new MemberSnapshot(
                m.id(), m.externalId(), m.firstName(), m.lastName(), m.nickname(), m.email(), m.phone(),
                m.city(), m.gender(), m.status().name(), m.channel(), tier, m.registeredAt(),
                m.referralCode(), m.referredBy(), m.labels(), m.profileCompletedAt() != null,
                m.birthDate() == null ? null : m.birthDate().toString(),
                MemberAttributes.visible(MemberAttributes.parse(m.attributesJson())), subjectRef);
    }
}
