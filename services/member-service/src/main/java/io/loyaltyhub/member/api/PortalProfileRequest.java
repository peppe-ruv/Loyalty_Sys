package io.loyaltyhub.member.api;

import java.time.LocalDate;

/**
 * {@code PATCH /v1/portal/members/{id}} (docs/servizi/member-service.md §3): solo campi di profilo e consensi.
 * Stato, etichette, attributi e codice invito non sono modificabili dal portale.
 */
public record PortalProfileRequest(
        Long version,
        String firstName,
        String lastName,
        String nickname,
        String email,
        String phone,
        LocalDate birthDate,
        String city,
        Consents consents
) {
    public UpdateMemberRequest toUpdate() {
        return new UpdateMemberRequest(version, firstName, lastName, nickname, email, phone, birthDate, null, city, consents);
    }
}
