package io.loyaltyhub.campaign;

import io.loyaltyhub.campaign.infra.CampaignMemberSubjectLookup;
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
 * L'elenco «Guadagna» del portale con il membro solo dal token (F2-SEC-09, ADR-048, Q-410, Q-553, Q-554, Q-560; docs/06
 * §3.4 e §9): token OIDC veri firmati RS256 ({@link OidcTestTokens}), verificati da {@code OidcActorFilter} con gli stessi
 * validatori dell'avvio; i membri A e B legati da fatti veri {@code member.registered} sul bus embedded (proiezione
 * {@code subjectRef → membro}). Profilo {@code enterprise} in miniatura: {@code identity.mode=oidc}, l'header
 * {@code X-LH-Actor} è ignorato e nessun {@code memberId} della richiesta è mai una fonte del membro.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "loyaltyhub.identity.mode=oidc")
@Import(PortalOidcScenarios.TokenConfig.class)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
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

    private static final String GOLD = "CMP-GOLD-PURCHASE-PLAY";
    private static final String EVERYONE = "CMP-PURCHASE-BASE";
    private static final String PATH = "/v1/portal/campaigns";

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

    @Autowired
    private CampaignMemberSubjectLookup lookup;

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
        registry.add("spring.datasource.url", () -> base + "&currentSchema=campaign");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
        registry.add(IdentityGuard.SUBJECT_KEY_PROPERTY, () -> TestSubjectKeys.base64(SUBJECT_KEY));
    }

    /** A, B e C registrati e legati da fatti veri, con livelli diversi per riconoscere il pubblico di ciascuno. */
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
        // A e C salgono a GOLD, B resta BASE: CMP-GOLD-PURCHASE-PLAY (pubblico GOLD/PLATINUM) distingue i tre
        awaitCommitted(MemberFactsSupport.tierUpgraded(A, "GOLD", "2026-09-01T11:00:00Z"),
                MemberFactsSupport.tierUpgraded(C, "GOLD", "2026-09-01T11:00:00Z"));
    }

    // ---------- il membro dal token: il proprio pubblico, mai quello altrui ----------

    @Test
    @DisplayName("[TB-CMP-MBP-020] A (GOLD) e B (BASE) leggono «Guadagna»: ciascuno il proprio pubblico, mai l'id o i dati dell'altro")
    void memberSeesOnlyTheAudienceOfTheToken() {
        Reply a = get(PATH, TOKENS.member(SUB_A));
        Reply b = get(PATH, TOKENS.member(SUB_B));
        assertThat(a.status).as(a.text).isEqualTo(200);
        assertThat(b.status).as(b.text).isEqualTo(200);
        assertThat(codes(a)).contains(EVERYONE, GOLD);
        assertThat(codes(b)).contains(EVERYONE).doesNotContain(GOLD);
        assertThat(a.body.get(0).path("rewardSummary").asString()).isNotBlank();
        // proprietà generica: nessuna risposta contiene un id di membro, né l'e-mail o lo username del token
        for (Reply r : List.of(a, b)) {
            assertThat(r.text).doesNotContain("MBR-").doesNotContain(A).doesNotContain(B).doesNotContain(C)
                    .doesNotContain(OidcTestTokens.emailOf(SUB_A)).doesNotContain(OidcTestTokens.usernameOf(SUB_A))
                    .doesNotContain(OidcTestTokens.emailOf(SUB_B)).doesNotContain(OidcTestTokens.usernameOf(SUB_B));
        }
    }

    @Test
    @DisplayName("[TB-CMP-MBP-021] con codes= il membro del token vede le campagne per codice (anche non elencate), col proprio pubblico")
    void codesAreResolvedForTheTokenMember() {
        String referral = "codes=CMP-REFERRAL-REFERRER,CMP-REFERRAL-REFEREE";
        for (String sub : List.of(SUB_A, SUB_B)) {
            Reply r = get(PATH + "?" + referral, TOKENS.member(sub));
            assertThat(r.status).as(r.text).isEqualTo(200);
            assertThat(codes(r)).containsExactlyInAnyOrder("CMP-REFERRAL-REFERRER", "CMP-REFERRAL-REFEREE");
        }
        // una campagna a pubblico GOLD per codice: A la vede, B no (il pubblico è quello del token, non un parametro)
        assertThat(codes(get(PATH + "?codes=" + GOLD, TOKENS.member(SUB_A)))).containsExactly(GOLD);
        assertThat(codes(get(PATH + "?codes=" + GOLD, TOKENS.member(SUB_B)))).isEmpty();
    }

    @Test
    @DisplayName("[TB-CMP-MBP-022] memberId in query in qualunque grafia, anche il proprio: 400 MEMBER_FROM_TOKEN")
    void memberIdInTheQueryIsRefused() {
        String token = TOKENS.member(SUB_A);
        for (String path : List.of(PATH + "?memberId=" + B, PATH + "?memberId=" + A, PATH + "?MEMBERID=" + B,
                PATH + "?member_id=" + B, PATH + "?!memberId=" + B, PATH + "?filter.memberId=" + B,
                PATH + "?codes=" + GOLD + "&memberId=" + B, PATH + "?memberId=" + A + "&codes=" + GOLD)) {
            Reply r = get(path, token);
            assertThat(r.status).as(path + " " + r.text).isEqualTo(400);
            assertThat(r.body.path("code").asString()).as(path).isEqualTo("MEMBER_FROM_TOKEN");
            assertThat(r.text).as(path).doesNotContain(B).doesNotContain(GOLD);
        }
    }

    @Test
    @DisplayName("[TB-CMP-MBP-023] il portale di campaign non ha scritture: un memberId in campo form o nel corpo non è mai una fonte (405)")
    void memberIdInAWriteIsNeverASource() {
        // Q-573 non si applica: nessun handler del portale di campaign legge un corpo o un form, quindi il ramo «memberId nel
        // corpo ⇒ 400 MEMBER_FROM_TOKEN» di MemberBodyAdvice (che vede solo DTO già deserializzati) non è raggiungibile qui: la
        // richiesta è respinta con 405 prima dell'interceptor. Lo copre EndpointAccessInterceptorTest di lh-common.
        String token = TOKENS.member(SUB_A);
        Reply form = request(HttpMethod.POST, PATH, token, null, MediaType.APPLICATION_FORM_URLENCODED, "memberId=" + B);
        Reply json = request(HttpMethod.POST, PATH, token, null, MediaType.APPLICATION_JSON, "{\"memberId\":\"" + B + "\"}");
        Reply put = request(HttpMethod.PUT, PATH, token, null, MediaType.APPLICATION_JSON, "{\"memberId\":\"" + B + "\"}");
        for (Reply r : List.of(form, json, put)) {
            assertThat(r.status).as(r.text).isEqualTo(405);
            assertThat(r.text).doesNotContain(B);
        }
    }

    @Test
    @DisplayName("[TB-CMP-MBP-024] X-LH-Member in oidc: 400 MEMBER_FROM_TOKEN, anche con il proprio id")
    void demoMemberHeaderIsRefused() {
        for (String id : List.of(B, A)) {
            Reply r = request(HttpMethod.GET, PATH, TOKENS.member(SUB_A), id, null, null);
            assertThat(r.status).as(r.text).isEqualTo(400);
            assertThat(r.body.path("code").asString()).isEqualTo("MEMBER_FROM_TOKEN");
        }
    }

    // ---------- operatori, token misti, fonti ----------

    @Test
    @DisplayName("[TB-CMP-MBP-025] un operatore CARE non agisce come membro: vista generica (BO-17 con codes=), mai personalizzata")
    void operatorGetsTheGenericView() {
        String token = TOKENS.operator("carla", "CARE");
        Reply generic = get(PATH, token);
        assertThat(generic.status).as(generic.text).isEqualTo(200);
        assertThat(codes(generic)).contains(EVERYONE).doesNotContain(GOLD); // nessun pubblico: come un membro senza legame
        // BO-17: legge le regole del referral per codice, senza membro (Q-560: il BFF non manda mai X-LH-Member)
        Reply referral = get(PATH + "?codes=CMP-REFERRAL-REFERRER,CMP-REFERRAL-REFEREE", token);
        assertThat(referral.status).as(referral.text).isEqualTo(200);
        assertThat(codes(referral)).containsExactlyInAnyOrder("CMP-REFERRAL-REFERRER", "CMP-REFERRAL-REFEREE");
        // ma un memberId o X-LH-Member non lo fa diventare un membro: 400 anche per un operatore
        Reply withMember = get(PATH + "?memberId=" + A, token);
        assertThat(withMember.status).as(withMember.text).isEqualTo(400);
        assertThat(withMember.body.path("code").asString()).isEqualTo("MEMBER_FROM_TOKEN");
        assertThat(request(HttpMethod.GET, PATH, token, A, null, null).status).isEqualTo(400);
    }

    @Test
    @DisplayName("[TB-CMP-MBP-026] token misto MEMBER+CARE: vale come operatore, vista generica (mai il pubblico del membro)")
    void mixedTokenIsNotAMember() {
        // Q-554: nemmeno se il sub è quello di A (GOLD) l'elenco è personalizzato; su un handler REQUIRED sarebbe 403 MEMBER_REQUIRED
        Reply r = get(PATH, TOKENS.mixed(SUB_A, "CARE"));
        assertThat(r.status).as(r.text).isEqualTo(200);
        assertThat(codes(r)).contains(EVERYONE).doesNotContain(GOLD);
    }

    @Test
    @DisplayName("[TB-CMP-MBP-027] MEMBER+SOURCE: 403 sul portale, mai membro")
    void memberPlusSourceIsForbidden() {
        Reply r = get(PATH, TOKENS.memberSource(SUB_A, "src-ecommerce"));
        assertThat(r.status).as(r.text).isEqualTo(403);
        assertThat(r.text).doesNotContain(GOLD);
    }

    @Test
    @DisplayName("[TB-CMP-MBP-034] token di sola SOURCE (senza MEMBER): 403 FORBIDDEN_ROLE sul portale, mai la vista generica")
    void pureSourceTokenIsForbidden() {
        String token = TOKENS.custom(TOKENS.base("src-ecommerce", 300).claim("lh_roles", List.of("SOURCE")).claim("azp", "src-ecommerce").build());
        Reply r = get(PATH, token);
        assertThat(r.status).as(r.text).isEqualTo(403);
        assertThat(r.body.path("code").asString()).isEqualTo("FORBIDDEN_ROLE");
        assertThat(r.text).doesNotContain(GOLD);
    }

    @Test
    @DisplayName("[TB-CMP-MBP-028] un token di membro sulle API di backoffice (R5 senza members): 403 FORBIDDEN_ROLE")
    void memberTokenCannotReachBackofficeApis() {
        String token = TOKENS.member(SUB_A);
        for (String path : List.of("/v1/campaigns", "/v1/campaigns/" + EVERYONE, "/v1/campaigns/" + EVERYONE + "/stats",
                "/v1/campaigns/" + EVERYONE + "/approval-history", "/v1/evaluations")) {
            Reply r = get(path, token);
            assertThat(r.status).as(path + " " + r.text).isEqualTo(403);
            assertThat(r.body.path("code").asString()).as(path).isEqualTo("FORBIDDEN_ROLE");
            assertThat(r.text).as(path).doesNotContain("\"trigger\"");
        }
        for (String path : List.of("/v1/campaigns/validate", "/v1/campaigns/simulate", "/v1/campaigns")) {
            Reply r = request(HttpMethod.POST, path, token, null, MediaType.APPLICATION_JSON, "{}");
            assertThat(r.status).as(path + " " + r.text).isEqualTo(403);
            assertThat(r.body.path("code").asString()).as(path).isEqualTo("FORBIDDEN_ROLE");
        }
    }

    @Test
    @DisplayName("[TB-CMP-MBP-029] X-LH-Actor è ignorato con un token: il membro resta membro")
    void actorHeaderIsIgnored() {
        Reply r = request(HttpMethod.GET, "/v1/campaigns", TOKENS.member(SUB_A), null, null, null, "ADMIN:intruso");
        assertThat(r.status).as(r.text).isEqualTo(403);
        assertThat(r.body.path("code").asString()).isEqualTo("FORBIDDEN_ROLE");
    }

    // ---------- legame assente, anonimizzato, token non validi ----------

    @Test
    @DisplayName("[TB-CMP-MBP-030] un sub non ancora legato: vista generica (OPTIONAL, non 409), poi personalizzata appena arriva il fatto")
    void unlinkedSubjectGetsTheGenericViewUntilTheFactArrives() {
        String token = TOKENS.member(SUB_NEW);
        Reply r = get(PATH, token);
        assertThat(r.status).as(r.text).isEqualTo(200); // OPTIONAL: nessun errore, nessun Retry-After
        assertThat(r.retryAfter).isNull();
        assertThat(codes(r)).contains(EVERYONE).doesNotContain(GOLD);

        String member = String.format("MBR-%06d", idBase() + 4);
        awaitCommitted(MemberFactsSupport.registered(member, ref(SUB_NEW), "2026-09-02T10:00:00Z", 2));
        awaitCommitted(MemberFactsSupport.tierUpgraded(member, "GOLD", "2026-09-02T11:00:00Z"));
        Reply linked = get(PATH, token);
        assertThat(linked.status).as(linked.text).isEqualTo(200);
        assertThat(codes(linked)).contains(EVERYONE, GOLD);
    }

    @Test
    @DisplayName("[TB-CMP-MBP-031] C anonimizzato: vista generica, e il replay del fatto di registrazione non ri-lega")
    void anonymizedMemberIsUnlinkedForGood() {
        String token = TOKENS.member(SUB_C);
        assertThat(codes(get(PATH, token))).contains(GOLD);

        awaitCommitted(MemberFactsSupport.statusChanged(C, "ANONYMIZED", "2026-09-03T10:00:00Z"));
        Reply gone = get(PATH, token);
        assertThat(gone.status).as(gone.text).isEqualTo(200);
        assertThat(codes(gone)).contains(EVERYONE).doesNotContain(GOLD);
        assertThat(jdbc.sql("SELECT subject_erased FROM member_snapshot WHERE member_id = ?").param(C).query(Boolean.class).single())
                .isTrue();

        // replay del fatto di registrazione e un member.updated più recente: la lapide vince
        awaitCommitted(MemberFactsSupport.registered(C, ref(SUB_C), "2026-09-01T10:00:00Z", 2),
                MemberFactsSupport.updated(C, ref(SUB_C), "2026-09-05T10:00:00Z", 2));
        assertThat(codes(get(PATH, token))).doesNotContain(GOLD);
        assertThat(jdbc.sql("SELECT count(*) FROM member_snapshot WHERE subject_ref = ?").param(ref(SUB_C)).query(Long.class).single())
                .isZero();
    }

    @Test
    @DisplayName("[TB-CMP-MBP-032] token assente, scaduto, firmato con un'altra chiave, audience o emittente sbagliati: 401")
    void invalidTokensAreUnauthorized() {
        assertThat(get(PATH, null).status).isEqualTo(401);
        assertThat(get(PATH, TOKENS.expired(SUB_A)).status).isEqualTo(401);
        assertThat(get(PATH, TOKENS.foreignKey(SUB_A)).status).isEqualTo(401);
        assertThat(get(PATH, TOKENS.wrongAudience(SUB_A)).status).isEqualTo(401);
        assertThat(get(PATH, TOKENS.wrongIssuer(SUB_A)).status).isEqualTo(401);
        assertThat(get(PATH + "?codes=" + GOLD, TOKENS.expired(SUB_A)).status).isEqualTo(401);
    }

    @Test
    @DisplayName("[TB-CMP-MBP-033] la lookup di campaign non è autorevole e serve solo il proprio modulo")
    void lookupIsNonAuthoritativeAndScoped() {
        assertThat(lookup.authoritative()).isFalse();
        assertThat(lookup.modulePackage()).isEqualTo("io.loyaltyhub.campaign");
        assertThat(lookup.memberId(ref(SUB_A))).contains(A);
        assertThat(lookup.memberId(ref("mai-visto-" + idBase()))).isEmpty();
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

    private static List<String> codes(Reply reply) {
        List<String> out = new java.util.ArrayList<>();
        if (reply.body != null && reply.body.isArray()) {
            reply.body.forEach(v -> out.add(v.path("code").asString()));
        }
        return out;
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
