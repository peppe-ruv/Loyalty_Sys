package io.loyaltyhub.ingestion;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.loyaltyhub.common.config.IdentityGuard;
import io.loyaltyhub.common.kafka.LoyaltyHubProperties;
import io.loyaltyhub.common.web.OidcActorFilter;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le fonti nel profilo {@code enterprise} (Q-492, F2-SEC-07, F2-IAM-02, ADR-027, docs/18 §3.2 e §3.10): access token
 * veri (firmati RS256 con una chiave del test, verificati da {@code OidcActorFilter} con gli stessi validatori
 * dell'avvio) di un client credentials {@code src-<codice>} con {@code lh_roles = [SOURCE]}. L'identità viene solo
 * dal token: l'header {@code X-LH-Actor} è ignorato. Il ruolo {@code SOURCE} arriva solo all'ingresso, e solo per la
 * fonte del proprio client.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "loyaltyhub.identity.mode=oidc")
@Import(SourceAuthOidcIT.TokenConfig.class)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.dlq.v1", "lh.audit.v1", "lh.facts.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SourceAuthOidcIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String ISSUER = "https://idp.example.test/realms/loyaltyhub";
    private static final KeyPair KEYS = rsa();
    private static final KeyPair OTHER_KEYS = rsa();

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcClient jdbc;

    /** Al posto del decoder che leggerebbe il JWKS dell'IdP: la chiave pubblica del test, stessi validatori. */
    @TestConfiguration
    static class TokenConfig {
        @Bean
        OidcActorFilter oidcActorFilter(LoyaltyHubProperties props) {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
            decoder.setJwtValidator(IdentityGuard.validator(ISSUER, "hub"));
            return new OidcActorFilter(props.getService(), decoder, "lh_roles");
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=ingestion");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    @DisplayName("[Q-492] token di src-ecommerce: l'evento della propria fonte è accettato")
    void ownSourceIsAccepted() throws Exception {
        String id = id();
        Reply r = post("/v1/events", sourceToken("src-ecommerce"), event(id, "ecommerce"));
        assertThat(r.status).as(r.text).isEqualTo(202);
        assertThat(r.body.path("status").asString()).isEqualTo("ACCEPTED");
        assertThat(count("inbound_event", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("[Q-492] token di src-ecommerce con la fonte di un altro client: 403 SOURCE_MISMATCH, nulla salvato")
    void otherSourceIsMismatch() throws Exception {
        String id = id();
        Reply r = post("/v1/events", sourceToken("src-ecommerce"), event(id, "partner"));
        assertThat(r.status).as(r.text).isEqualTo(403);
        assertThat(r.body.path("code").asString()).isEqualTo("SOURCE_MISMATCH");
        assertThat(count("inbound_event", id)).isZero();
        assertThat(count("outbox", id)).isZero();
    }

    @Test
    @DisplayName("[Q-492] l'header X-LH-Actor è ignorato: un token SOURCE con X-LH-Actor ADMIN resta SOURCE")
    void actorHeaderIsIgnored() throws Exception {
        String id = id();
        Reply r = post("/v1/events", sourceToken("src-ecommerce"), event(id, "partner"), "ADMIN:intruso");
        assertThat(r.status).as(r.text).isEqualTo(403);
        assertThat(r.body.path("code").asString()).isEqualTo("SOURCE_MISMATCH");
        assertThat(count("inbound_event", id)).isZero();
    }

    @Test
    @DisplayName("[Q-492] il client è quello del token (azp), non lo username dell'utenza di servizio né il nome del client web")
    void clientComesFromTheToken() throws Exception {
        // Client che non è di una fonte (per esempio web) con il ruolo SOURCE: nessuna fonte consentita.
        String id = id();
        Reply r = post("/v1/events", sourceToken("web"), event(id, "ecommerce"));
        assertThat(r.status).as(r.text).isEqualTo(403);
        assertThat(r.body.path("code").asString()).isEqualTo("SOURCE_MISMATCH");
        assertThat(count("inbound_event", id)).isZero();
    }

    @Test
    @DisplayName("[Q-492] token di un operatore, di sola lettura o di ADMIN: solo ADMIN passa, per qualunque fonte")
    void operatorsAndAdmin() throws Exception {
        String marketing = post("/v1/events", token("luca", "web", List.of("MARKETING")), event(id(), "ecommerce")).body
                .path("code").asString();
        assertThat(marketing).isEqualTo("FORBIDDEN_ROLE");
        assertThat(post("/v1/events", token("sara", "web", List.of("ANALYST")), event(id(), "ecommerce")).status).isEqualTo(403);
        assertThat(post("/v1/events", token("nessuno", "web", List.of()), event(id(), "ecommerce")).status).isEqualTo(403);
        Reply admin = post("/v1/events", token("marta", "web", List.of("ADMIN")), event(id(), "partner"));
        assertThat(admin.status).as(admin.text).isEqualTo(202);
    }

    @Test
    @DisplayName("[Q-492] SOURCE con un ruolo operatore: valgono i ruoli operatore, SOURCE non è mai un'escalation")
    void sourceNeverEscalates() throws Exception {
        String id = id();
        Reply r = post("/v1/events", token("service-account-src-ecommerce", "src-ecommerce", List.of("SOURCE", "MARKETING")),
                event(id, "ecommerce"));
        assertThat(r.status).as(r.text).isEqualTo(403);
        assertThat(r.body.path("code").asString()).isEqualTo("FORBIDDEN_ROLE");
        assertThat(count("inbound_event", id)).isZero();
    }

    @Test
    @DisplayName("[Q-492] SOURCE non raggiunge nessun altro endpoint: registro fonti, monitor, simulatore, tipi azione")
    void sourceReachesOnlyTheIngress() throws Exception {
        String token = sourceToken("src-ecommerce");
        for (String path : List.of("/v1/sources", "/v1/inbound-events", "/v1/event-types", "/v1/demo/scenarios")) {
            Reply r = get(path, token);
            assertThat(r.status).as(path + " " + r.text).isEqualTo(403);
            assertThat(r.body.path("code").asString()).as(path).isEqualTo("FORBIDDEN_ROLE");
        }
        // Attuatori e api-docs (percorsi che l'interceptor degli endpoint non vede) sono chiusi dal filtro.
        for (String path : List.of("/actuator/metrics", "/actuator/prometheus", "/v3/api-docs")) {
            Reply r = get(path, token);
            assertThat(r.status).as(path + " " + r.text).isEqualTo(403);
            assertThat(r.body.path("code").asString()).as(path).isEqualTo("FORBIDDEN_ROLE");
        }
        assertThat(get("/actuator/health", null).status).isEqualTo(200);
        Reply fire = post("/v1/demo/simulator/fire", token, Map.of("type", "app.login.daily"));
        assertThat(fire.status).as(fire.text).isEqualTo(403);
        assertThat(get("/v1/sources", token("marta", "web", List.of("ADMIN"))).status).isEqualTo(200);
    }

    @Test
    @DisplayName("[Q-492] batch: un elemento di un'altra fonte respinge l'intera richiesta; senza mismatch tutto è accettato")
    void batch() throws Exception {
        String token = sourceToken("src-ecommerce");
        String a = id();
        String b = id();
        Reply mixed = post("/v1/events/batch", token, List.of(event(a, "ecommerce"), event(b, "partner")));
        assertThat(mixed.status).as(mixed.text).isEqualTo(403);
        assertThat(mixed.body.path("code").asString()).isEqualTo("SOURCE_MISMATCH");
        assertThat(count("inbound_event", a) + count("inbound_event", b)).isZero();
        assertThat(count("outbox", a) + count("outbox", b)).isZero();

        Reply own = post("/v1/events/batch", token, List.of(event(a, "ecommerce"), event(b, "ecommerce")));
        assertThat(own.status).as(own.text).isEqualTo(202);
        assertThat(own.body.path("counts").path("accepted").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("[Q-492] transazioni: stessa regola degli eventi")
    void transactions() throws Exception {
        String token = sourceToken("src-ecommerce");
        Map<String, Object> ok = txn("ecommerce");
        Reply own = post("/v1/transactions", token, ok);
        assertThat(own.status).as(own.text).isEqualTo(202);
        assertThat(own.body.path("status").asString()).isEqualTo("ACCEPTED");
        Map<String, Object> other = txn("partner");
        Reply mismatch = post("/v1/transactions", token, other);
        assertThat(mismatch.status).as(mismatch.text).isEqualTo(403);
        assertThat(mismatch.body.path("code").asString()).isEqualTo("SOURCE_MISMATCH");
        assertThat(count("inbound_event", "txn-" + other.get("orderId"))).isZero();
    }

    @Test
    @DisplayName("[Q-492] senza token, con firma altrui, audience sbagliata o scaduto: 401, nulla salvato")
    void invalidTokens() throws Exception {
        String id = id();
        assertThat(post("/v1/events", null, event(id, "ecommerce"), "SOURCE:src-ecommerce").status).isEqualTo(401);
        assertThat(post("/v1/events", sign(claims("src-ecommerce", "src-ecommerce", List.of("SOURCE"), "hub", 300), OTHER_KEYS),
                event(id, "ecommerce")).status).isEqualTo(401);
        assertThat(post("/v1/events", sign(claims("src-ecommerce", "src-ecommerce", List.of("SOURCE"), "widgets", 300), KEYS),
                event(id, "ecommerce")).status).isEqualTo(401);
        assertThat(post("/v1/events", sign(claims("src-ecommerce", "src-ecommerce", List.of("SOURCE"), "hub", -600), KEYS),
                event(id, "ecommerce")).status).isEqualTo(401);
        assertThat(count("inbound_event", id)).isZero();
    }

    // ---------- supporto ----------

    private record Reply(int status, String text, JsonNode body) {
    }

    private static String id() {
        return "oidc-" + UUID.randomUUID();
    }

    private long count(String table, String eventId) {
        if (table.equals("outbox")) {
            return jdbc.sql("SELECT count(*) FROM outbox WHERE payload->>'id' = ?").param(eventId).query(Long.class).single();
        }
        return jdbc.sql("SELECT count(*) FROM inbound_event WHERE event_id = ?").param(eventId).query(Long.class).single();
    }

    private Map<String, Object> event(String id, String source) {
        Map<String, Object> event = new HashMap<>();
        event.put("specversion", "1.0");
        event.put("id", id);
        event.put("source", "urn:loyaltyhub:source:" + source);
        event.put("type", "purchase.completed");
        event.put("subject", "member:MBR-000002");
        event.put("time", Instant.now().toString());
        event.put("data", Map.of("orderId", "ORD-" + id, "amount", 130, "currency", "EUR", "channel", "ONLINE"));
        return event;
    }

    private Map<String, Object> txn(String source) {
        Map<String, Object> t = new HashMap<>();
        t.put("source", source);
        t.put("orderId", "OIDC-" + UUID.randomUUID());
        t.put("memberRef", "member:MBR-000002");
        t.put("amount", 64.9);
        t.put("currency", "EUR");
        return t;
    }

    private Reply get(String path, String token) {
        return call("GET", path, token, null, null);
    }

    private Reply post(String path, String token, Object body) {
        return call("POST", path, token, body, null);
    }

    private Reply post(String path, String token, Object body, String actorHeader) {
        return call("POST", path, token, body, actorHeader);
    }

    private Reply call(String method, String path, String token, Object body, String actorHeader) {
        var spec = RestClient.create("http://localhost:" + port)
                .method(org.springframework.http.HttpMethod.valueOf(method)).uri(path);
        if (token != null) {
            spec = spec.header("Authorization", "Bearer " + token);
        }
        if (actorHeader != null) {
            spec = spec.header("X-LH-Actor", actorHeader);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes());
            return new Reply(res.getStatusCode().value(), text, text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text));
        });
    }

    /** Access token del client credentials di una fonte: {@code azp = clientId}, utenza di servizio, {@code lh_roles = [SOURCE]}. */
    private static String sourceToken(String clientId) throws Exception {
        return token("service-account-" + clientId, clientId, List.of("SOURCE"));
    }

    private static String token(String username, String clientId, List<String> roles) throws Exception {
        return sign(claims(username, clientId, roles, "hub", 300), KEYS);
    }

    private static JWTClaimsSet claims(String username, String clientId, List<String> roles, String audience, long ttl) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("sub-" + clientId)
                .audience(audience)
                .issueTime(Date.from(now.minusSeconds(60)))
                .expirationTime(Date.from(now.plusSeconds(ttl)))
                .claim("preferred_username", username)
                .claim("azp", clientId)
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
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
