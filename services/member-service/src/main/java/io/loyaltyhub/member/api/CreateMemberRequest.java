package io.loyaltyhub.member.api;

/** Corpo di {@code POST /v1/members} (docs/servizi/member-service.md §3). {@code referralCode} opzionale. */
public record CreateMemberRequest(
        String firstName,
        String lastName,
        String nickname,
        String email,
        String phone,
        String city,
        String gender,
        String channel,
        String referralCode
) {
}
