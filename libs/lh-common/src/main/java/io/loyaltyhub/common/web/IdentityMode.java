package io.loyaltyhub.common.web;

/**
 * Da dove viene l'attore di una richiesta (ADR-027, CLAUDE.md regole 6 e 6-bis).
 * {@code HEADER}: header {@code X-LH-Actor}, solo nel profilo {@code demo}. {@code OIDC}: access token verificato
 * (firma, {@code iss}, {@code aud}, scadenza); obbligatorio nel profilo {@code enterprise}.
 */
public enum IdentityMode {
    HEADER,
    OIDC
}
