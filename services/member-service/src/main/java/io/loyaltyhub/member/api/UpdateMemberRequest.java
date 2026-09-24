package io.loyaltyhub.member.api;

import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.List;

/**
 * Corpo di {@code PATCH /v1/members/{id}} (docs §3): PATCH parziale con {@code version} (lock ottimistico).
 * I campi assenti ({@code null}) non vengono toccati. {@code attributes}: chiave → valore, {@code null} rimuove
 * (F-MBR-03); {@code labels}: elenco completo delle etichette.
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
        String city,
        Consents consents,
        JsonNode attributes,
        List<String> labels
) {
}
