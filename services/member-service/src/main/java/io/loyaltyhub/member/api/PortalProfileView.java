package io.loyaltyhub.member.api;

import java.time.LocalDate;
import java.util.List;

/**
 * Profilo visto dal portale (docs/09 PT-08, {@code GET /v1/portal/members/{id}}): dati personali, consensi e
 * completezza {@code {completed, missingFields[]}} per l'indicatore "Completa il profilo".
 */
public record PortalProfileView(
        String memberId,
        String firstName,
        String lastName,
        String nickname,
        String email,
        String phone,
        LocalDate birthDate,
        String city,
        Consents consents,
        String referralCode,
        long version,
        Completeness completeness
) {
    public record Completeness(boolean completed, List<String> missingFields) {
    }
}
