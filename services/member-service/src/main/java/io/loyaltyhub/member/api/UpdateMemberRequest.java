package io.loyaltyhub.member.api;

import java.time.LocalDate;

/**
 * Corpo di {@code PATCH /v1/members/{id}} (docs §3): PATCH parziale con {@code version} (lock ottimistico).
 * I campi assenti ({@code null}) non vengono toccati.
 */
public record UpdateMemberRequest(
        Long version,
        String firstName,
        String lastName,
        String nickname,
        String email,
        String phone,
        LocalDate birthDate,
        String gender,
        String city
) {
}
