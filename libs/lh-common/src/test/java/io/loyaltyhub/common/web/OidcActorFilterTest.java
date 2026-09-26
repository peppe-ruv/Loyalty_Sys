package io.loyaltyhub.common.web;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.loyaltyhub.common.config.IdentityGuard;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Attore dal token nel profilo enterprise (ADR-027, docs/18 §3.2, CLAUDE.md regola 6-bis): token veri firmati RS256
 * con una chiave generata nel test, verificati dallo stesso decoder e dagli stessi validatori dell'avvio.
 */
class OidcActorFilterTest {

    private static final String ISSUER = "https://idp.example.test/realms/loyaltyhub";
    private static final KeyPair KEYS = rsa();
    private static final KeyPair OTHER_KEYS = rsa();

    private final OidcActorFilter filter = new OidcActorFilter("wallet", decoder(), "lh_roles");

    @Test
    @DisplayName("token valido di un operatore: attore dal token, l'header X-LH-Actor è ignorato")
    void validOperatorToken() throws Exception {
        Result r = call("/v1/campaigns", token(claims("luca.marketing", List.of("MARKETING"), "hub", ISSUER, 300)),
                "ADMIN:intruso");
        assertThat(r.status).isEqualTo(200);
        assertThat(r.actor.get()).isEqualTo(new ActorContext(Role.MARKETING, "luca.marketing"));
    }

    @Test
    @DisplayName("senza token, token malformato, firma di un'altra chiave, emittente o audience diversi, scaduto: 401")
    void invalidTokens() throws Exception {
        assertThat(call("/v1/campaigns", null, "ADMIN:intruso").status).isEqualTo(401);
        assertThat(call("/v1/campaigns", "non-un-jwt", null).status).isEqualTo(401);
        assertThat(call("/v1/campaigns", sign(claims("x", List.of("ADMIN"), "hub", ISSUER, 300), OTHER_KEYS), null).status)
                .isEqualTo(401);
        assertThat(call("/v1/campaigns", token(claims("x", List.of("ADMIN"), "hub", "https://altro/realms/x", 300)), null)
                .status).isEqualTo(401);
        assertThat(call("/v1/campaigns", token(claims("x", List.of("ADMIN"), "widgets", ISSUER, 300)), null).status)
                .isEqualTo(401);
        assertThat(call("/v1/campaigns", token(claims("x", List.of("ADMIN"), "hub", ISSUER, -600)), null).status)
                .isEqualTo(401);
        MockHttpServletResponse res = call("/v1/campaigns", null, null).response;
        assertThat(res.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(res.getContentType()).startsWith("application/problem+json");
        assertThat(res.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    @DisplayName("token di solo membro: vale sul portale (sub disponibile), 403 sulle API del backoffice")
    void memberToken() throws Exception {
        String member = token(claims("mario", List.of("MEMBER"), "hub", ISSUER, 300));
        Result portal = call("/v1/portal/members/me/wallet", member, null);
        assertThat(portal.status).isEqualTo(200);
        assertThat(portal.actor.get().role()).isEqualTo(Role.ANALYST);
        assertThat(portal.request.getAttribute(OidcActorFilter.MEMBER_SUBJECT_ATTRIBUTE)).isEqualTo("sub-mario");
        assertThat(call("/v1/members", member, null).status).isEqualTo(403);
    }

    @Test
    @DisplayName("probe di salute senza token")
    void probesArePublic() throws Exception {
        assertThat(call("/actuator/health", null, null).status).isEqualTo(200);
        assertThat(call("/actuator/health/readiness", null, null).status).isEqualTo(200);
        assertThat(call("/actuator/metrics", null, null).status).isEqualTo(401);
    }

    @Test
    @DisplayName("[Q-365] più ruoli: ADMIN vince; più ruoli operatore diversi o nessuno ⇒ sola lettura")
    void rolesFromToken() {
        assertThat(ActorContext.fromToken(List.of("MARKETING", "ADMIN"), "a").role()).isEqualTo(Role.ADMIN);
        assertThat(ActorContext.fromToken(List.of("LEGAL"), "a").role()).isEqualTo(Role.LEGAL);
        assertThat(ActorContext.fromToken(List.of("MARKETING", "LEGAL"), "a").role()).isEqualTo(Role.ANALYST);
        assertThat(ActorContext.fromToken(List.of("MEMBER"), "a").role()).isEqualTo(Role.ANALYST);
        assertThat(ActorContext.fromToken(List.of("marketing"), "a").role()).isEqualTo(Role.ANALYST);
        assertThat(ActorContext.fromToken(null, null)).isEqualTo(ActorContext.ANONYMOUS);
    }

    // ---- supporto ----

    private record Result(int status, AtomicReference<ActorContext> actor, MockHttpServletRequest request,
                          MockHttpServletResponse response) {
    }

    private Result call(String path, String bearer, String actorHeader) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        if (bearer != null) {
            req.addHeader("Authorization", "Bearer " + bearer);
        }
        if (actorHeader != null) {
            req.addHeader(ActorFilter.HEADER, actorHeader);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<ActorContext> seen = new AtomicReference<>();
        FilterChain chain = (rq, rs) -> seen.set(ActorHolder.get());
        filter.doFilter(req, res, chain);
        assertThat(ActorHolder.get()).as("pulito a fine richiesta").isEqualTo(ActorContext.ANONYMOUS);
        return new Result(res.getStatus(), seen, req, res);
    }

    private static NimbusJwtDecoder decoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
        decoder.setJwtValidator(IdentityGuard.validator(ISSUER, "hub"));
        return decoder;
    }

    private static JWTClaimsSet claims(String user, List<String> roles, String audience, String issuer, long ttlSeconds) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("sub-" + user)
                .audience(audience)
                .issueTime(Date.from(now.minusSeconds(60)))
                .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
                .claim("preferred_username", user)
                .claim("lh_roles", roles)
                .build();
    }

    private static String token(JWTClaimsSet claims) throws Exception {
        return sign(claims, KEYS);
    }

    private static String sign(JWTClaimsSet claims, KeyPair keys) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
        return jwt.serialize();
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
