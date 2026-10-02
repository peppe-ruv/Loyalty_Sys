package io.loyaltyhub.common.config;

import io.loyaltyhub.common.identity.SubjectRef;
import io.loyaltyhub.common.web.IdentityMode;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;

import java.util.Base64;

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
        String memberIssuer = env.getProperty(MEMBER_ISSUER_PROPERTY, "").trim();
        if (!memberIssuer.isEmpty()) {
            if (mode != IdentityMode.OIDC) {
                throw new IllegalStateException("INSECURE_CONFIG: LH_OIDC_MEMBER_ISSUER vale solo con "
                        + "loyaltyhub.identity.mode=oidc (ADR-051).");
            }
            if (sameIssuer(memberIssuer, env.getProperty("loyaltyhub.identity.issuer-uri", ""))) {
                throw new IllegalStateException("INSECURE_CONFIG: LH_OIDC_MEMBER_ISSUER coincide con LH_OIDC_ISSUER: "
                        + "il realm dei membri deve essere un emittente distinto (ADR-051).");
            }
        } else if (!env.getProperty("loyaltyhub.identity.member-jwk-set-uri", "").isBlank()) {
            throw new IllegalStateException("INSECURE_CONFIG: LH_OIDC_MEMBER_JWKS_URI senza LH_OIDC_MEMBER_ISSUER.");
        }
        if (testUsersAllowed(env) && !"test".equals(env.getProperty(ENVIRONMENT_PROPERTY, "").trim())) {
            throw new IllegalStateException("INSECURE_CONFIG: LH_TEST_USERS_ALLOWED=true vale solo con LH_ENVIRONMENT=test: "
                    + "gli utenti di test hanno credenziali pubbliche (ADR-051, Q-676).");
        }
        return mode;
    }

    /** Ruolo che marca gli utenti di test della vetrina, nel claim dei ruoli del token (ADR-051, Q-676). */
    public static final String TEST_USER_ROLE = "LH_TEST_USER";
    public static final String TEST_USERS_ALLOWED_PROPERTY = "loyaltyhub.identity.test-users-allowed";
    public static final String ENVIRONMENT_PROPERTY = "loyaltyhub.environment";

    /** Vero solo con {@code LH_TEST_USERS_ALLOWED=true}: ogni altro valore, o l'assenza, rifiuta gli utenti di test. */
    public static boolean testUsersAllowed(Environment env) {
        return "true".equalsIgnoreCase(env.getProperty(TEST_USERS_ALLOWED_PROPERTY, "").trim());
    }

    /**
     * Proprietà dell'emittente dei membri ({@code LH_OIDC_MEMBER_ISSUER}, ADR-051 decisione 6): facoltativa. Se c'è, il
     * ruolo {@code MEMBER} vale solo dai suoi token e i suoi token portano solo {@code MEMBER}.
     */
    public static final String MEMBER_ISSUER_PROPERTY = "loyaltyhub.identity.member-issuer-uri";

    static boolean sameIssuer(String a, String b) {
        return a.trim().replaceAll("/+$", "").equals(b.trim().replaceAll("/+$", ""));
    }

    /** Proprietà con la chiave dello pseudonimo del soggetto ({@code LH_SUBJECT_KEY}, base64, almeno 32 byte; Q-552). */
    public static final String SUBJECT_KEY_PROPERTY = "loyaltyhub.identity.subject-key";

    /**
     * La chiave dello pseudonimo {@code subjectRef} ({@code LH_SUBJECT_KEY}), decodificata; {@code null} se non
     * configurata. Se è configurata ma non è base64 valido o è più corta di {@value SubjectRef#MIN_KEY_BYTES} byte, l'avvio
     * fallisce con {@code INSECURE_CONFIG} (regola 22, ADR-044); il messaggio non riporta mai il valore (regola 20).
     */
    public static byte[] subjectKey(Environment env) {
        String raw = env.getProperty(SUBJECT_KEY_PROPERTY, "").trim();
        if (raw.isEmpty()) {
            return null;
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(raw);
        } catch (IllegalArgumentException standard) {
            try {
                key = Base64.getUrlDecoder().decode(raw);
            } catch (IllegalArgumentException url) {
                throw new IllegalStateException("INSECURE_CONFIG: LH_SUBJECT_KEY non è base64 valido (serve una chiave "
                        + "casuale di almeno " + SubjectRef.MIN_KEY_BYTES + " byte in base64).");
            }
        }
        if (key.length < SubjectRef.MIN_KEY_BYTES) {
            throw new IllegalStateException("INSECURE_CONFIG: LH_SUBJECT_KEY è più corta di " + SubjectRef.MIN_KEY_BYTES
                    + " byte (serve una chiave casuale in base64).");
        }
        return key;
    }

    /**
     * Come {@link #subjectKey(Environment)}, ma la chiave è obbligatoria: con {@code oidc} e almeno un handler
     * {@code @MemberEndpoint} una chiave assente o corta fa fallire l'avvio con {@code INSECURE_CONFIG} (Q-552, ADR-048,
     * regola 22). Il profilo {@code demo} non ne usa: {@code X-LH-Member} e {@code memberId} esplicito bastano.
     */
    public static byte[] requireSubjectKey(Environment env) {
        byte[] key = subjectKey(env);
        if (key == null) {
            throw new IllegalStateException("INSECURE_CONFIG: gli endpoint del membro nel profilo oidc richiedono "
                    + "loyaltyhub.identity.subject-key (LH_SUBJECT_KEY, base64, almeno " + SubjectRef.MIN_KEY_BYTES
                    + " byte): senza, il token non si lega al membro.");
        }
        return key;
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
