package io.loyaltyhub.common.web;

/**
 * Le sole rivendicazioni del token di un membro che servono a risolvere il membro (emittente e soggetto): le mette
 * {@link OidcActorFilter} come attributo di richiesta ({@link OidcActorFilter#MEMBER_TOKEN_ATTRIBUTE}) e le legge solo
 * {@code MemberPrincipals}. Mai loggate ({@link #toString()} mascherato, regola 20).
 */
record MemberTokenClaims(String issuer, String subject) {

    @Override
    public String toString() {
        return "MemberTokenClaims[***]";
    }
}
