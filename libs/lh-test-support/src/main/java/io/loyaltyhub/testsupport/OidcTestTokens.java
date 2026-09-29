package io.loyaltyhub.testsupport;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Token OIDC veri, firmati RS256 con una chiave generata per l'istanza, per gli IT e i test del membro dal token
 * (Q-410, ADR-048): il modello è {@code OidcActorFilterTest} e {@code SourceAuthOidcIT}. Solo Nimbus: nessuna dipendenza
 * da {@code lh-common} né da Spring; chi verifica i token costruisce il decoder con {@link #publicKey()}, {@link #issuer()}
 * e {@link #audience()} ({@code NimbusJwtDecoder.withPublicKey(...)} e {@code IdentityGuard.validator(...)}).
 *
 * <p>Ogni token ha {@code iss}, {@code sub}, {@code aud=hub}, {@code iat}, {@code exp}, il claim dei ruoli
 * ({@code lh_roles}) e, per i token con una persona, {@code preferred_username} ed {@code email}: servono ai test per
 * provare che né l'uno né l'altra finiscano mai nell'attore, nell'MDC, nell'audit o nelle risposte (regola 20).
 * Sono dati fittizi ({@code example.test}); nessuna chiave è fissa nel repository.
 */
public final class OidcTestTokens {

    public static final String DEFAULT_ISSUER = "https://idp.example.test/realms/loyaltyhub";
    public static final String DEFAULT_AUDIENCE = "hub";
    public static final String DEFAULT_ROLES_CLAIM = "lh_roles";

    private static final KeyPair FOREIGN = rsa();

    private final KeyPair keys = rsa();
    private final String issuer;
    private final String audience;
    private final String rolesClaim;

    /** Emittente, audience e claim dei ruoli di default. */
    public OidcTestTokens() {
        this(DEFAULT_ISSUER, DEFAULT_AUDIENCE, DEFAULT_ROLES_CLAIM);
    }

    public OidcTestTokens(String issuer, String audience, String rolesClaim) {
        this.issuer = issuer;
        this.audience = audience;
        this.rolesClaim = rolesClaim;
    }

    public RSAPublicKey publicKey() {
        return (RSAPublicKey) keys.getPublic();
    }

    public String issuer() {
        return issuer;
    }

    public String audience() {
        return audience;
    }

    /** L'e-mail fittizia del token di {@code sub}, per provare che non compare mai altrove. */
    public static String emailOf(String sub) {
        return sub + "@example.test";
    }

    /** Il {@code preferred_username} fittizio del token di {@code sub}. */
    public static String usernameOf(String sub) {
        return "utente." + sub;
    }

    /** Token di solo membro: {@code lh_roles = [MEMBER]}. */
    public String member(String sub) {
        return sign(person(sub, List.of("MEMBER"), 300), keys);
    }

    /** Token di un operatore: {@code lh_roles = roles} (per esempio {@code CARE}, {@code ADMIN}). */
    public String operator(String username, String... roles) {
        JWTClaimsSet.Builder claims = base("op-" + username, 300).claim("preferred_username", username)
                .claim(rolesClaim, List.of(roles));
        return sign(claims.build(), keys);
    }

    /** Token misto: {@code MEMBER} più ruoli di operatore ({@code lh_roles = [MEMBER, roles…]}). */
    public String mixed(String sub, String... operatorRoles) {
        List<String> roles = new ArrayList<>(List.of("MEMBER"));
        roles.addAll(List.of(operatorRoles));
        return sign(person(sub, roles, 300), keys);
    }

    /** Token {@code MEMBER} più {@code SOURCE} (l'utenza di una fonte con il ruolo membro), client {@code clientId}. */
    public String memberSource(String sub, String clientId) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder(person(sub, List.of("MEMBER", "SOURCE"), 300))
                .claim("azp", clientId);
        return sign(claims.build(), keys);
    }

    /** Token di un membro già scaduto (firma e ruoli validi). */
    public String expired(String sub) {
        return sign(person(sub, List.of("MEMBER"), -600), keys);
    }

    /** Token di un membro firmato con una chiave che il decoder non conosce. */
    public String foreignKey(String sub) {
        return sign(person(sub, List.of("MEMBER"), 300), FOREIGN);
    }

    /** Token di un membro con un'audience diversa da quella dei servizi. */
    public String wrongAudience(String sub) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder(person(sub, List.of("MEMBER"), 300))
                .audience("widgets");
        return sign(claims.build(), keys);
    }

    /** Token di un membro con un emittente diverso da quello atteso. */
    public String wrongIssuer(String sub) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder(person(sub, List.of("MEMBER"), 300))
                .issuer("https://altro.example.test/realms/x");
        return sign(claims.build(), keys);
    }

    /** Token con i ruoli e le rivendicazioni scelti dal chiamante (per i casi di confine). */
    public String custom(JWTClaimsSet claims) {
        return sign(claims, keys);
    }

    /** Le rivendicazioni di base ({@code iss}, {@code sub}, {@code aud}, {@code iat}, {@code exp}) di {@code sub}. */
    public JWTClaimsSet.Builder base(String sub, long ttlSeconds) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(sub)
                .audience(audience)
                .issueTime(Date.from(now.minusSeconds(60)))
                .expirationTime(Date.from(now.plusSeconds(ttlSeconds)));
    }

    private JWTClaimsSet person(String sub, List<String> roles, long ttlSeconds) {
        return base(sub, ttlSeconds)
                .claim("preferred_username", usernameOf(sub))
                .claim("email", emailOf(sub))
                .claim(rolesClaim, roles)
                .build();
    }

    private static String sign(JWTClaimsSet claims, KeyPair keys) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Firma del token di prova non riuscita", e);
        }
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
