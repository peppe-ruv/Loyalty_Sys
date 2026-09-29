package io.loyaltyhub.member.api;

/**
 * Corpo di {@code POST /v1/portal/members} (PT-16, F-MBR-06, Q-157, ADR-048): la registrazione dal portale. Nessun
 * {@code memberId}, {@code externalId}, {@code status} né {@code channel}: l'id lo assegna il servizio, il canale è
 * sempre {@code PORTAL}, lo stato {@code ACTIVE} e l'account (il {@code sub}) viene solo dal token. I campi che il
 * client aggiunge comunque sono ignorati, compreso un {@code memberId} nel corpo (SPEC-GAP: Q-573: docs/06 §3.4
 * vorrebbe {@code 400 MEMBER_FROM_TOKEN} in {@code enterprise}, ma {@code MemberBodyAdvice} vede solo l'oggetto
 * deserializzato; nessun campo nascosto come ripiego).
 * {@code email} è obbligatoria e arriva dal modulo, non dal token (nessuna verifica e-mail, Q-557): registrarsi con
 * l'e-mail di un altro non dà accesso a nulla.
 * <p>SPEC-GAP: Q-574 — il progetto (§3.4 B1) prevedeva {@code locale?}: il membro non ha una colonna {@code locale}, il
 * campo sarebbe accettato e scartato, quindi è rinviato a M8.4 (recapito, {@code member.registered:2}).
 */
public record PortalRegistrationRequest(
        String firstName,
        String lastName,
        String nickname,
        String email,
        String phone,
        String city,
        Consents consents,
        String referralCode
) {
    /** Il comando interno di creazione: canale e stato non sono mai del chiamante. */
    public CreateMemberRequest toCreate() {
        return new CreateMemberRequest(firstName, lastName, nickname, email, phone, city, null,
                CreateMemberRequest.PORTAL_CHANNEL, referralCode, consents);
    }
}
