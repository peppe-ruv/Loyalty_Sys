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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Due realm, due emittenti (ADR-051 decisione 6): ogni realm firma con la sua chiave e ogni emittente porta solo i suoi
 * ruoli. Operatori, fonti e job dal realm {@code loyaltyhub}; {@code MEMBER} solo dal realm {@code loyaltyhub-members}.
 */
class TwoIssuerOidcActorFilterTest {

    private static final String OPERATORS = "https://idp.example.test/realms/loyaltyhub";
    private static final String MEMBERS = "https://idp.example.test/realms/loyaltyhub-members";
    private static final KeyPair OPERATOR_KEYS = rsa();
    private static final KeyPair MEMBER_KEYS = rsa();

    private final OidcActorFilter filter = new OidcActorFilter("wallet", new IssuerRoutingJwtDecoder(Map.of(
            OPERATORS, decoder(OPERATOR_KEYS, OPERATORS), MEMBERS, decoder(MEMBER_KEYS, MEMBERS))), "lh_roles", MEMBERS);

    @Test
    @DisplayName("membro dal suo realm: vale sul portale con emittente e soggetto, 403 sulle API del backoffice")
    void memberFromMemberRealm() throws Exception {
        String member = sign(claims("anna", List.of("MEMBER"), MEMBERS), MEMBER_KEYS);
        Result portal = call("/v1/portal/me", member);
        assertThat(portal.status).isEqualTo(200);
        assertThat(portal.request.getAttribute(OidcActorFilter.MEMBER_TOKEN_ATTRIBUTE))
                .isEqualTo(new MemberTokenClaims(MEMBERS, "sub-anna"));
        assertThat(call("/v1/members", member).status).isEqualTo(403);
    }

    @Test
    @DisplayName("realm dei membri con un ruolo da operatore, SOURCE o senza MEMBER: 401, anche sul portale")
    void memberRealmCannotCarryOperatorRoles() throws Exception {
        assertThat(call("/v1/campaigns", sign(claims("x", List.of("MEMBER", "ADMIN"), MEMBERS), MEMBER_KEYS)).status)
                .isEqualTo(401);
        assertThat(call("/v1/portal/me", sign(claims("x", List.of("ADMIN"), MEMBERS), MEMBER_KEYS)).status)
                .isEqualTo(401);
        assertThat(call("/v1/events", sign(claims("x", List.of("SOURCE"), MEMBERS), MEMBER_KEYS)).status)
                .isEqualTo(401);
        assertThat(call("/v1/portal/me", sign(claims("x", List.of(), MEMBERS), MEMBER_KEYS)).status).isEqualTo(401);
    }

    @Test
    @DisplayName("token di solo membro dal realm operatori: 403 ovunque, anche sul portale")
    void memberOnlyFromOperatorRealmRefused() throws Exception {
        String token = sign(claims("x", List.of("MEMBER"), OPERATORS), OPERATOR_KEYS);
        assertThat(call("/v1/portal/me", token).status).isEqualTo(403);
        assertThat(call("/v1/campaigns", token).status).isEqualTo(403);
    }

    @Test
    @DisplayName("operatore dal suo realm: il ruolo predefinito MEMBER non conta, l'attore è l'operatore")
    void operatorFromOperatorRealm() throws Exception {
        Result r = call("/v1/campaigns", sign(claims("marta.admin", List.of("ADMIN", "MEMBER"), OPERATORS),
                OPERATOR_KEYS));
        assertThat(r.status).isEqualTo(200);
        assertThat(r.actor.get()).isEqualTo(new ActorContext(Role.ADMIN, "marta.admin"));
        assertThat(r.request.getAttribute(OidcActorFilter.MEMBER_TOKEN_ATTRIBUTE)).isNull();
    }

    @Test
    @DisplayName("chiave dell'altro realm, emittente sconosciuto o assente: 401")
    void wrongKeyOrIssuer() throws Exception {
        assertThat(call("/v1/portal/me", sign(claims("x", List.of("MEMBER"), MEMBERS), OPERATOR_KEYS)).status)
                .isEqualTo(401);
        assertThat(call("/v1/campaigns", sign(claims("x", List.of("ADMIN"), OPERATORS), MEMBER_KEYS)).status)
                .isEqualTo(401);
        assertThat(call("/v1/campaigns", sign(claims("x", List.of("ADMIN"), "https://altro/realms/x"), OPERATOR_KEYS))
                .status).isEqualTo(401);
        assertThat(call("/v1/campaigns", sign(claims("x", List.of("ADMIN"), null), OPERATOR_KEYS)).status)
                .isEqualTo(401);
        assertThat(call("/v1/campaigns", "non-un-jwt").status).isEqualTo(401);
    }

    @Test
    @DisplayName("[Q-676] utente di test: 401 in ogni realm, salvo con gli utenti di test ammessi")
    void testUsersRefusedUnlessAllowed() throws Exception {
        String operator = sign(claims("marta.admin", List.of("ADMIN", "LH_TEST_USER"), OPERATORS), OPERATOR_KEYS);
        String member = sign(claims("anna", List.of("MEMBER", "LH_TEST_USER"), MEMBERS), MEMBER_KEYS);
        assertThat(call("/v1/campaigns", operator).status).isEqualTo(401);
        assertThat(call("/v1/portal/me", member).status).isEqualTo(401);
        OidcActorFilter allowed = new OidcActorFilter("wallet", new IssuerRoutingJwtDecoder(Map.of(
                OPERATORS, decoder(OPERATOR_KEYS, OPERATORS), MEMBERS, decoder(MEMBER_KEYS, MEMBERS))), "lh_roles",
                MEMBERS, true);
        assertThat(call(allowed, "/v1/campaigns", operator).status).isEqualTo(200);
        assertThat(call(allowed, "/v1/campaigns", operator).actor.get()).isEqualTo(new ActorContext(Role.ADMIN, "marta.admin"));
        assertThat(call(allowed, "/v1/portal/me", member).status).isEqualTo(200);
    }

    private record Result(int status, AtomicReference<ActorContext> actor, MockHttpServletRequest request) {
    }

    private Result call(String path, String bearer) throws Exception {
        return call(filter, path, bearer);
    }

    private static Result call(OidcActorFilter filter, String path, String bearer) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.addHeader("Authorization", "Bearer " + bearer);
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<ActorContext> seen = new AtomicReference<>();
        FilterChain chain = (rq, rs) -> seen.set(ActorHolder.get());
        filter.doFilter(req, res, chain);
        return new Result(res.getStatus(), seen, req);
    }

    private static NimbusJwtDecoder decoder(KeyPair keys, String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build();
        decoder.setJwtValidator(IdentityGuard.validator(issuer, "hub"));
        return decoder;
    }

    private static JWTClaimsSet claims(String user, List<String> roles, String issuer) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("sub-" + user)
                .audience("hub")
                .issueTime(Date.from(now.minusSeconds(60)))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("preferred_username", user)
                .claim("lh_roles", roles)
                .build();
    }

    private static String sign(JWTClaimsSet claims, KeyPair keys) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
        return jwt.serialize();
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
