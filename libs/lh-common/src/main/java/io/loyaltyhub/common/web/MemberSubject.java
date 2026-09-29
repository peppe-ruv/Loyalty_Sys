package io.loyaltyhub.common.web;

/**
 * Il soggetto del token per gli handler {@link MemberEndpoint.Mode#REGISTRATION} (Q-551, ADR-048): l'account non ancora
 * (o già) legato a un membro. Contiene il {@code sub} del token, un dato personale: non si logga, non va sul bus e non si
 * copia in nessuna risposta ({@link #toString()} lo maschera).
 *
 * @param issuer         {@code iss} del token ({@code null} nel profilo demo)
 * @param subject        {@code sub} del token ({@code null} nel profilo demo)
 * @param subjectRef     {@code HMAC-SHA256(LH_SUBJECT_KEY, iss‖sub)}, lo pseudonimo che viaggia sul bus
 *                       ({@link io.loyaltyhub.common.identity.SubjectRef})
 * @param linkedMemberId il membro già legato a questo account, o {@code null} se non ancora registrato
 */
public record MemberSubject(String issuer, String subject, String subjectRef, String linkedMemberId) {

    /** Attributo di richiesta con il soggetto risolto (letto dall'argument resolver, mai da un controller). */
    public static final String ATTRIBUTE = "io.loyaltyhub.member.subject";

    /** Profilo demo: nessun account, la registrazione crea un nuovo membro come oggi. */
    public static MemberSubject demo() {
        return new MemberSubject(null, null, null, null);
    }

    /** Vero nel profilo demo (nessun token). */
    public boolean isDemo() {
        return issuer == null;
    }

    /** Vero se l'account ha già un membro. */
    public boolean linked() {
        return linkedMemberId != null;
    }

    /** Regola 20 (CLAUDE.md): il {@code sub} e l'emittente non compaiono mai in un log o in un messaggio. */
    @Override
    public String toString() {
        return "MemberSubject[***]";
    }
}
