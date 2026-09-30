package io.loyaltyhub.wallet;

import io.loyaltyhub.common.config.IdentityGuard;
import io.loyaltyhub.common.identity.SubjectRef;
import io.loyaltyhub.common.kafka.LoyaltyHubProperties;
import io.loyaltyhub.common.web.OidcActorFilter;
import io.loyaltyhub.testsupport.ListenerGroups;
import io.loyaltyhub.testsupport.OidcTestTokens;
import io.loyaltyhub.testsupport.TestSubjectKeys;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Il wallet del portale con il membro solo dal token (F2-SEC-09, ADR-048, Q-410, Q-493, Q-553, Q-554; docs/06 §3.4 e
 * §9): token OIDC veri firmati RS256 ({@link OidcTestTokens}), verificati da {@code OidcActorFilter} con gli stessi
 * validatori dell'avvio; i membri A e B legati da fatti veri {@code member.registered} sul bus embedded (proiezione
 * {@code subjectRef → membro}). Profilo {@code enterprise} in miniatura: {@code identity.mode=oidc}, l'header
 * {@code X-LH-Actor} è ignorato e nessun {@code memberId} della richiesta è mai una fonte del membro.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "loyaltyhub.identity.mode=oidc")
@Import(PortalOidcScenarios.TokenConfig.class)
@EmbeddedKafka(partitions = 1, topics = {"lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PortalOidcScenarios {

    private static final EmbeddedPostgres PG = startPg();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                PG.close();
            } catch (Exception ignored) {
                // chiusura best effort a fine JVM
            }
        }));
    }
    private static final OidcTestTokens TOKENS = new OidcTestTokens();
    private static final byte[] SUBJECT_KEY = TestSubjectKeys.random();

    /** Base numerica degli id dei membri di questa classe concreta: due classi sullo stesso contesto non si pestano i piedi. */
    abstract int idBase();

    private String A;
    private String B;
    private String C; // poi anonimizzato
    private String SUB_A;
    private String SUB_B;
    private String SUB_C;
    private String SUB_NEW;

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    /** Al posto del decoder che leggerebbe il JWKS dell'IdP: la chiave pubblica del test, stessi validatori. */
    @TestConfiguration
    static class TokenConfig {
        @Bean
        OidcActorFilter oidcActorFilter(LoyaltyHubProperties props) {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(TOKENS.publicKey()).build();
            decoder.setJwtValidator(IdentityGuard.validator(TOKENS.issuer(), TOKENS.audience()));
            return new OidcActorFilter(props.getService(), decoder, OidcTestTokens.DEFAULT_ROLES_CLAIM);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=wallet");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
        registry.add(IdentityGuard.SUBJECT_KEY_PROPERTY, () -> TestSubjectKeys.base64(SUBJECT_KEY));
    }

    /** A, B e C registrati e legati da fatti veri; A e B con punti diversi, per riconoscere i dati di ciascuno. */
    @BeforeAll
    void linkMembers() {
        A = String.format("MBR-%06d", idBase() + 1);
        B = String.format("MBR-%06d", idBase() + 2);
        C = String.format("MBR-%06d", idBase() + 3);
        SUB_A = "sub-utente-a-" + idBase();
        SUB_B = "sub-utente-b-" + idBase();
        SUB_C = "sub-utente-c-" + idBase();
        SUB_NEW = "sub-utente-non-legato-" + idBase();
        ListenerGroups.awaitStable(listeners);
        awaitCommitted(
                MemberFactsSupport.registered(A, ref(SUB_A), "2026-09-01T10:00:00Z", 2),
                MemberFactsSupport.registered(B, ref(SUB_B), "2026-09-01T10:00:00Z", 1),
                MemberFactsSupport.registered(C, ref(SUB_C), "2026-09-01T10:00:00Z", 2));
        awaitCommitted(MemberFactsSupport.grant(A, "EFF-OIDC-A-" + idBase(), 111), MemberFactsSupport.grant(B, "EFF-OIDC-B-" + idBase(), 222));
    }

    // ---------- il membro dal token: i propri dati, mai quelli altrui ----------

    @Test
    @DisplayName("[TB-WAL-MBP-020] A legge il proprio wallet e le proprie attività: 200, e l'id di B non compare mai")
    void memberReadsOnlyOwnData() {
        Reply wallet = get("/v1/portal/me/wallet", TOKENS.member(SUB_A));
        assertThat(wallet.status).as(wallet.text).isEqualTo(200);
        assertThat(wallet.body.path("memberId").asString()).isEqualTo(A);
        assertThat(wallet.body.path("balances").path("PTS").path("active").asLong()).isEqualTo(111);

        Reply activity = get("/v1/portal/me/wallet/activity?size=5", TOKENS.member(SUB_A));
        assertThat(activity.status).as(activity.text).isEqualTo(200);
        assertThat(activity.body.get(0).path("amount").asLong()).isEqualTo(111);

        Reply other = get("/v1/portal/me/wallet", TOKENS.member(SUB_B));
        assertThat(other.body.path("memberId").asString()).isEqualTo(B);
        assertThat(other.body.path("balances").path("PTS").path("active").asLong()).isEqualTo(222);

        // proprietà generica: nessuna risposta di A contiene l'id di B, né l'e-mail o lo username del token
        for (Reply r : List.of(wallet, activity,
                get("/v1/portal/tiers", TOKENS.member(SUB_A)), get("/v1/portal/editions", TOKENS.member(SUB_A)))) {
            assertThat(r.status).as(r.text).isEqualTo(200);
            assertThat(r.text).doesNotContain(B).doesNotContain(OidcTestTokens.emailOf(SUB_A))
                    .doesNotContain(OidcTestTokens.usernameOf(SUB_A));
        }
    }

    @Test
    @DisplayName("[TB-WAL-MBP-021] alias /v1/portal/editions e livelli: aperti ai membri, uguali per tutti")
    void readOnlyCatalogsAreOpenToMembers() {
        Reply editions = get("/v1/portal/editions", TOKENS.member(SUB_A));
        assertThat(editions.status).as(editions.text).isEqualTo(200);
        assertThat(editions.body.isArray()).isTrue();
        assertThat(get("/v1/portal/tiers", TOKENS.member(SUB_B)).status).isEqualTo(200);
        // ...ma con un memberId nella richiesta sono rifiutate come ogni altra funzione del portale
        assertThat(get("/v1/portal/editions?memberId=" + B, TOKENS.member(SUB_A)).status).isEqualTo(400);
        assertThat(get("/v1/portal/tiers?memberId=" + A, TOKENS.member(SUB_A)).status).isEqualTo(400);
    }

    @Test
    @DisplayName("[TB-WAL-MBP-022] memberId in query in qualunque grafia, anche il proprio: 400 MEMBER_FROM_TOKEN")
    void memberIdInTheQueryIsRefused() {
        String token = TOKENS.member(SUB_A);
        for (String path : List.of("/v1/portal/me/wallet?memberId=" + B, "/v1/portal/me/wallet?memberId=" + A,
                "/v1/portal/me/wallet?MEMBERID=" + B, "/v1/portal/me/wallet?member_id=" + B,
                "/v1/portal/me/wallet?!memberId=" + B, "/v1/portal/me/wallet?filter.memberId=" + B,
                "/v1/portal/me/wallet/activity?memberId=" + B, "/v1/portal/me/wallet/activity?size=3&memberId=" + A)) {
            Reply r = get(path, token);
            assertThat(r.status).as(path + " " + r.text).isEqualTo(400);
            assertThat(r.body.path("code").asString()).as(path).isEqualTo("MEMBER_FROM_TOKEN");
            assertThat(r.text).as(path).doesNotContain(B);
        }
    }

    @Test
    @DisplayName("[TB-WAL-MBP-023] un memberId come campo form non è una fonte del membro (il wallet non ha scritture del portale)")
    void memberIdAsFormFieldIsNeverASource() {
        Reply r = request(HttpMethod.POST, "/v1/portal/me/wallet", TOKENS.member(SUB_A), null,
                MediaType.APPLICATION_FORM_URLENCODED, "memberId=" + B);
        // POST non è una mappatura del wallet: 405 prima ancora dell'interceptor. Il ramo «campo form ⇒ 400 MEMBER_FROM_TOKEN»
        // qui non è raggiungibile (nessuna scrittura del portale); lo copre EndpointAccessInterceptorTest di lh-common.
        assertThat(r.status).as(r.text).isEqualTo(405);
        assertThat(r.text).doesNotContain(B);
        assertThat(get("/v1/portal/me/wallet", TOKENS.member(SUB_B)).body.path("balances").path("PTS").path("active").asLong())
                .isEqualTo(222);
    }

    @Test
    @DisplayName("[TB-WAL-MBP-024] X-LH-Member in oidc: 400 MEMBER_FROM_TOKEN, anche con il proprio id")
    void demoMemberHeaderIsRefused() {
        for (String id : List.of(B, A)) {
            Reply r = request(HttpMethod.GET, "/v1/portal/me/wallet", TOKENS.member(SUB_A), id, null, null);
            assertThat(r.status).as(r.text).isEqualTo(400);
            assertThat(r.body.path("code").asString()).isEqualTo("MEMBER_FROM_TOKEN");
        }
    }

    @Test
    @DisplayName("[TB-WAL-MBP-025] percorsi legacy con l'id nel percorso: 403 MEMBER_FROM_TOKEN, con l'id di B e con il proprio")
    void legacyPathsAreForbidden() {
        String token = TOKENS.member(SUB_A);
        for (String path : List.of("/v1/portal/wallets/" + B, "/v1/portal/wallets/" + A,
                "/v1/portal/wallets/" + B + "/activity", "/v1/portal/wallets/" + A + "/activity")) {
            Reply r = get(path, token);
            assertThat(r.status).as(path + " " + r.text).isEqualTo(403);
            assertThat(r.body.path("code").asString()).as(path).isEqualTo("MEMBER_FROM_TOKEN");
            assertThat(r.text).as(path).doesNotContain("\"balances\"").doesNotContain("111").doesNotContain("222");
        }
    }

    // ---------- operatori, token misti, fonti ----------

    @Test
    @DisplayName("[TB-WAL-MBP-026] un operatore CARE non agisce come membro: 403 MEMBER_REQUIRED su /me/wallet")
    void operatorIsNotAMember() {
        for (String path : List.of("/v1/portal/me/wallet", "/v1/portal/me/wallet/activity")) {
            Reply r = get(path, TOKENS.operator("carla", "CARE"));
            assertThat(r.status).as(path + " " + r.text).isEqualTo(403);
            assertThat(r.body.path("code").asString()).isEqualTo("MEMBER_REQUIRED");
        }
    }

    @Test
    @DisplayName("[TB-WAL-MBP-027] token misto MEMBER+CARE: vale come operatore, 403 sulle funzioni del membro")
    void mixedTokenIsNotAMember() {
        Reply r = get("/v1/portal/me/wallet", TOKENS.mixed(SUB_A, "CARE"));
        assertThat(r.status).as(r.text).isEqualTo(403);
        assertThat(r.body.path("code").asString()).isEqualTo("MEMBER_REQUIRED");
        assertThat(r.text).doesNotContain(A);
    }

    @Test
    @DisplayName("[TB-WAL-MBP-028] MEMBER+SOURCE: 403 sul portale, mai membro")
    void memberPlusSourceIsForbidden() {
        String token = TOKENS.memberSource(SUB_A, "src-ecommerce");
        for (String path : List.of("/v1/portal/me/wallet", "/v1/portal/tiers", "/v1/portal/editions")) {
            Reply r = get(path, token);
            assertThat(r.status).as(path + " " + r.text).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("[TB-WAL-MBP-029] un token di membro sulle API di backoffice (R5 senza members): 403 FORBIDDEN_ROLE")
    void memberTokenCannotReachBackofficeReads() {
        String token = TOKENS.member(SUB_A);
        for (String path : List.of("/v1/wallets/" + A, "/v1/wallets/" + B, "/v1/wallets/" + A + "/ledger", "/v1/editions",
                "/v1/tiers", "/v1/currencies")) {
            Reply r = get(path, token);
            assertThat(r.status).as(path + " " + r.text).isEqualTo(403);
            assertThat(r.body.path("code").asString()).as(path).isEqualTo("FORBIDDEN_ROLE");
            assertThat(r.text).as(path).doesNotContain("\"balances\"");
        }
    }

    @Test
    @DisplayName("[TB-WAL-MBP-030] X-LH-Actor è ignorato con un token: il membro resta membro")
    void actorHeaderIsIgnored() {
        Reply r = request(HttpMethod.GET, "/v1/wallets/" + B, TOKENS.member(SUB_A), null, null, null, "ADMIN:intruso");
        assertThat(r.status).as(r.text).isEqualTo(403);
        assertThat(r.body.path("code").asString()).isEqualTo("FORBIDDEN_ROLE");
    }

    // ---------- legame assente, anonimizzato, token non validi ----------

    @Test
    @DisplayName("[TB-WAL-MBP-031] un sub non ancora legato: 409 MEMBER_NOT_LINKED con Retry-After, poi 200 appena arriva il fatto")
    void unlinkedSubjectGets409UntilTheFactArrives() {
        String token = TOKENS.member(SUB_NEW);
        Reply r = get("/v1/portal/me/wallet", token);
        assertThat(r.status).as(r.text).isEqualTo(409);
        assertThat(r.body.path("code").asString()).isEqualTo("MEMBER_NOT_LINKED");
        assertThat(r.retryAfter).isEqualTo("2");
        assertThat(get("/v1/portal/me/wallet/activity", token).status).isEqualTo(409);

        String member = String.format("MBR-%06d", idBase() + 4);
        awaitCommitted(MemberFactsSupport.registered(member, ref(SUB_NEW), "2026-09-02T10:00:00Z", 2));
        Reply linked = get("/v1/portal/me/wallet", token);
        assertThat(linked.status).as(linked.text).isEqualTo(200);
        assertThat(linked.body.path("memberId").asString()).isEqualTo(member);
    }

    @Test
    @DisplayName("[TB-WAL-MBP-032] A anonimizzato: 409 e il replay del fatto di registrazione non ri-lega")
    void anonymizedMemberIsUnlinkedForGood() {
        String token = TOKENS.member(SUB_C);
        assertThat(get("/v1/portal/me/wallet", token).status).isEqualTo(200);

        awaitCommitted(MemberFactsSupport.statusChanged(C, "ANONYMIZED", "2026-09-03T10:00:00Z"));
        Reply gone = get("/v1/portal/me/wallet", token);
        assertThat(gone.status).as(gone.text).isEqualTo(409);
        assertThat(gone.body.path("code").asString()).isEqualTo("MEMBER_NOT_LINKED");
        assertThat(gone.retryAfter).isEqualTo("2");

        // replay del fatto di registrazione e un member.updated più recente: la lapide vince
        awaitCommitted(MemberFactsSupport.registered(C, ref(SUB_C), "2026-09-01T10:00:00Z", 2),
                MemberFactsSupport.updated(C, ref(SUB_C), "2026-09-05T10:00:00Z", 2));
        assertThat(get("/v1/portal/me/wallet", token).status).isEqualTo(409);
        assertThat(get("/v1/portal/me/wallet/activity", token).status).isEqualTo(409);
    }

    @Test
    @DisplayName("[TB-WAL-MBP-033] token assente, scaduto, firmato con un'altra chiave, audience o emittente sbagliati: 401")
    void invalidTokensAreUnauthorized() {
        assertThat(get("/v1/portal/me/wallet", null).status).isEqualTo(401);
        assertThat(get("/v1/portal/me/wallet", TOKENS.expired(SUB_A)).status).isEqualTo(401);
        assertThat(get("/v1/portal/me/wallet", TOKENS.foreignKey(SUB_A)).status).isEqualTo(401);
        assertThat(get("/v1/portal/me/wallet", TOKENS.wrongAudience(SUB_A)).status).isEqualTo(401);
        assertThat(get("/v1/portal/me/wallet", TOKENS.wrongIssuer(SUB_A)).status).isEqualTo(401);
        assertThat(get("/v1/portal/tiers", TOKENS.expired(SUB_A)).status).isEqualTo(401);
    }

    // ---------- supporto ----------

    private record Reply(int status, String text, JsonNode body, String retryAfter) {
    }

    private Reply get(String path, String token) {
        return request(HttpMethod.GET, path, token, null, null, null);
    }

    private Reply request(HttpMethod method, String path, String token, String memberHeader, MediaType contentType,
                          String body) {
        return request(method, path, token, memberHeader, contentType, body, null);
    }

    private Reply request(HttpMethod method, String path, String token, String memberHeader, MediaType contentType,
                          String body, String actor) {
        RestClient client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        RestClient.RequestBodySpec spec = client.method(method).uri(path);
        if (token != null) {
            spec.header("Authorization", "Bearer " + token);
        }
        if (memberHeader != null) {
            spec.header("X-LH-Member", memberHeader);
        }
        if (actor != null) {
            spec.header("X-LH-Actor", actor);
        }
        if (contentType != null) {
            spec.contentType(contentType);
        }
        if (body != null) {
            spec.body(body);
        }
        return spec.exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes());
            JsonNode node = text.isBlank() ? null : mapper.readTree(text);
            return new Reply(res.getStatusCode().value(), text, node, res.getHeaders().getFirst("Retry-After"));
        });
    }

    private void awaitCommitted(RecordMetadata... records) {
        ListenerGroups.awaitCommitted(listeners, List.of(records));
    }

    private String ref(String sub) {
        return SubjectRef.of(SUBJECT_KEY, TOKENS.issuer(), sub);
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
