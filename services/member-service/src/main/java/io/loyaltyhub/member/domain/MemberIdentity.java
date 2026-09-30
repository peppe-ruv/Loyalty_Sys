package io.loyaltyhub.member.domain;

import java.time.Instant;

/**
 * Legame tra un account OIDC {@code (issuer, subject)} e il membro (F2-IAM-03, ADR-048, Q-551). {@code subject} è un
 * dato personale: non entra in nessun evento né log (regola 20), e {@link #toString()} non lo stampa. Sul bus viaggia
 * solo {@code subjectRef}, l'HMAC-SHA256 di {@code (issuer, subject)} calcolato da {@code MemberPrincipals} con
 * {@code LH_SUBJECT_KEY} (Q-552).
 */
public record MemberIdentity(String memberId, String issuer, String subject, String subjectRef, Instant linkedAt) {

    @Override
    public String toString() {
        return "MemberIdentity[memberId=" + memberId + ", subjectRef=" + subjectRef + "]";
    }
}
