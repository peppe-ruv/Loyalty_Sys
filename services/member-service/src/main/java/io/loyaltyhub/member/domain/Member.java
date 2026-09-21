package io.loyaltyhub.member.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Anagrafica di un membro (docs/servizi/member-service.md §2). {@code consents} e {@code attributes}
 * sono JSON grezzo; {@code version} è il lock ottimistico delle PATCH (docs §3).
 */
public record Member(
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
        MemberStatus status,
        String channel,
        Instant registeredAt,
        String referralCode,
        String referredBy,
        Instant referralCompletedAt,
        String consentsJson,
        String attributesJson,
        List<String> labels,
        String avatarSeed,
        Instant profileCompletedAt,
        long version
) {
    /** Nome visualizzato: {@code first + " " + last}, o il nickname/id se anonimizzato. */
    public String displayName() {
        if (firstName != null && lastName != null) {
            return firstName + " " + lastName;
        }
        return nickname != null ? nickname : id;
    }
}
