package io.loyaltyhub.common.config;

import io.loyaltyhub.common.web.IdentityMode;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;

/**
 * Configurazione dell'identità (ADR-027, ADR-044, CLAUDE.md regole 6-bis e 22): il profilo {@code enterprise} non
 * parte con un'identità insicura. Con {@code enterprise} serve {@code loyaltyhub.identity.mode=oidc} e un
 * emittente; altrimenti l'avvio fallisce con {@code INSECURE_CONFIG}, mai con un avviso.
 */
public final class IdentityGuard {

    private IdentityGuard() {
    }

    /** Verifica la coerenza fra profilo e modalità d'identità; lancia {@link IllegalStateException} se insicura. */
    public static IdentityMode check(Environment env) {
        IdentityMode mode = mode(env.getProperty("loyaltyhub.identity.mode", "header"));
        boolean enterprise = env.acceptsProfiles(Profiles.of("enterprise"));
        if (enterprise && mode != IdentityMode.OIDC) {
            throw new IllegalStateException("INSECURE_CONFIG: il profilo enterprise richiede loyaltyhub.identity.mode=oidc "
                    + "(LH_IDENTITY_MODE=oidc); l'header X-LH-Actor vale solo nel profilo demo (ADR-027).");
        }
        if (mode == IdentityMode.OIDC && env.getProperty("loyaltyhub.identity.issuer-uri", "").isBlank()) {
            throw new IllegalStateException("INSECURE_CONFIG: loyaltyhub.identity.mode=oidc senza emittente "
                    + "(loyaltyhub.identity.issuer-uri / LH_OIDC_ISSUER).");
        }
        return mode;
    }

    static IdentityMode mode(String value) {
        try {
            return IdentityMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("INSECURE_CONFIG: loyaltyhub.identity.mode sconosciuta: " + value
                    + " (ammesse: header, oidc).");
        }
    }

    /** Validatori del token: scadenza e {@code nbf}, emittente esatto, audience che contiene {@code audience}. */
    public static OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "audience non ammessa", null));
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audienceValidator);
    }
}
