package io.loyaltyhub.member.api;

import java.time.LocalDate;
import java.util.List;

/**
 * Profilo visto dal portale (docs/09 PT-08, {@code GET /v1/portal/members/{id}}): dati personali, consensi e
 * completezza {@code {completed, missingFields[]}} per l'indicatore "Completa il profilo".
 * {@code GET /v1/portal/me/profile} è il percorso del membro dal token (ADR-048); {@code status} (PT-16, M8.2) dice
 * al portale se il membro è {@code ACTIVE}, {@code BLOCKED}… senza una seconda chiamata.
 */
public record PortalProfileView(
        String memberId,
        String status,
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
