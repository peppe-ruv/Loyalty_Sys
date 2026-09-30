package io.loyaltyhub.member.api;

/**
 * Corpo di {@code POST /v1/members} (docs/servizi/member-service.md §3), usato anche dalla registrazione del portale
 * ({@code /portal/join}, F-MBR-06). {@code referralCode} e {@code consents} opzionali.
 */
public record CreateMemberRequest(
        String firstName,
        String lastName,
        String nickname,
        String email,
        String phone,
        String city,
        String gender,
        String channel,
        String referralCode,
        Consents consents
) {

    /** Canale di una registrazione dal portale: lo imposta il servizio, mai il client (Q-157). */
    public static final String PORTAL_CHANNEL = "PORTAL";
}
