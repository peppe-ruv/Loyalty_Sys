package io.loyaltyhub.member;

import io.loyaltyhub.common.config.IdentityGuard;
import io.loyaltyhub.common.identity.SubjectRef;
import io.loyaltyhub.common.kafka.LoyaltyHubProperties;
import io.loyaltyhub.common.web.OidcActorFilter;
import io.loyaltyhub.common.web.ActorHolder;
import io.loyaltyhub.testsupport.OidcTestTokens;
import io.loyaltyhub.testsupport.TestSubjectKeys;
import io.loyaltyhub.testsupport.TopicReader;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base dei test del membro dal token nel profilo {@code enterprise} di member-service (M8.2, F2-IAM-03, F2-SEC-09,
 * ADR-048): {@code loyaltyhub.identity.mode=oidc} con token RS256 veri da {@link OidcTestTokens} (stessi validatori
 * dell'avvio, chiave pubblica del test al posto del JWKS), {@code LH_SUBJECT_KEY} di prova e Postgres/Kafka embedded.
 * Le sottoclassi condividono un solo contesto Spring.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "loyaltyhub.identity.mode=oidc",
        "loyaltyhub.member.segments.reannounce-delay-ms=0"})
@Import(OidcPortalSupport.TokenConfig.class)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class OidcPortalSupport {

    /** Chiave di prova casuale per il processo (32 byte): mai una chiave letterale (regola 20, gitleaks). */
    static final byte[] SUBJECT_KEY = TestSubjectKeys.random();
    static final String SUBJECT_KEY_BASE64 = TestSubjectKeys.base64(SUBJECT_KEY);
    static final OidcTestTokens TOKENS = new OidcTestTokens();
    static final String FACTS = "lh.facts.v1";
    static final String AUDIT = "lh.audit.v1";
    static final String REGISTERED = "io.loyaltyhub.fact.member.registered";
    static final String UPDATED = "io.loyaltyhub.fact.member.updated";
    static final String AUDIT_ENTRY = "io.loyaltyhub.audit.entry";

    private static final EmbeddedPostgres PG = startPg();
    /** Attore e MDC visti a fine richiesta: {@code metodo percorso -> actor}. */
    static final Queue<String> ACTORS = new ConcurrentLinkedQueue<>();

    final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    /** Al posto del decoder che leggerebbe il JWKS dell'IdP: la chiave pubblica del test, stessi validatori. */
    @TestConfiguration
    static class TokenConfig {
        @Bean
        OidcActorFilter oidcActorFilter(LoyaltyHubProperties props) {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) TOKENS.publicKey()).build();
            decoder.setJwtValidator(IdentityGuard.validator(TOKENS.issuer(), TOKENS.audience()));
            return new OidcActorFilter(props.getService(), decoder, OidcTestTokens.DEFAULT_ROLES_CLAIM);
        }

        /** Registra attore corrente e MDC a fine richiesta, prima che il filtro li ripulisca. */
        @Bean
        WebMvcConfigurer actorRecorder() {
            return new WebMvcConfigurer() {
                @Override
                public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(new HandlerInterceptor() {
                        @Override
                        public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                                    Exception ex) {
                            ACTORS.add(request.getMethod() + " " + request.getRequestURI() + " -> holder="
                                    + ActorHolder.get().asActorString() + " mdc=" + MDC.get("actor"));
                        }
                    }).order(Ordered.LOWEST_PRECEDENCE);
                }
            };
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(IdentityGuard.SUBJECT_KEY_PROPERTY, () -> SUBJECT_KEY_BASE64);
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=member");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    // ---------- helper ----------

    record Reply(int status, HttpHeaders headers, String text, JsonNode body) {
        String code() {
            return body.path("code").asString();
        }
    }

    static String newSub() {
        return "sub-" + UUID.randomUUID();
    }

    /** {@code subjectRef} atteso di un account del token di prova. */
    static String refOf(String sub) {
        return SubjectRef.of(SUBJECT_KEY, TOKENS.issuer(), sub);
    }

    /** E-mail del profilo: distinta dal sub e da quella del token, deterministica per account. */
    static String profileEmail(String sub) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(sub.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "profilo." + java.util.HexFormat.of().formatHex(hash, 0, 8) + "@profili.test";
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    Map<String, Object> registration(String sub) {
        return Map.of("firstName", "Aurora", "lastName", "Verdi", "nickname", "Aury", "email", profileEmail(sub),
                "city", "Modena", "consents", Map.of("marketing", true, "profiling", false));
    }

    /** Registra l'account e restituisce l'id del membro (201 la prima volta). */
    String register(String sub) {
        Reply r = call("POST", "/v1/portal/members", TOKENS.member(sub), registration(sub));
        assertThat(r.status()).as(r.text()).isEqualTo(201);
        return r.body().path("memberId").asString();
    }

    Reply call(String method, String path, String token) {
        return call(method, path, token, null);
    }

    Reply call(String method, String path, String token, Object body, String... headers) {
        var spec = RestClient.create("http://localhost:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (token != null) {
            spec = spec.header("Authorization", "Bearer " + token);
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            spec = spec.header(headers[i], headers[i + 1]);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes());
            return new Reply(res.getStatusCode().value(), res.getHeaders(), text,
                    text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text));
        });
    }

    /** Fatti su {@code lh.facts.v1} (e voci su {@code lh.audit.v1}) del soggetto e del tipo, dall'outbox o dal topic. */
    List<JsonNode> published(String topic, String subject, String type) {
        return new TopicReader(jdbc, mapper, topic).published(List.of(), subject, type);
    }

    long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
