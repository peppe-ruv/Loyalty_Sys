package io.loyaltyhub.reward;

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
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le API del portale di reward-service con il membro solo dal token (F2-SEC-09, ADR-048, Q-410, Q-493, Q-553, Q-554,
 * Q-556; docs/06 §3.4 e §9): token OIDC veri firmati RS256 ({@link OidcTestTokens}), verificati da
 * {@code OidcActorFilter} con gli stessi validatori dell'avvio; i membri A e B legati da fatti veri
 * {@code member.registered} sul bus embedded (proiezione {@code subjectRef → membro}). Profilo {@code enterprise} in
 * miniatura: {@code identity.mode=oidc}, l'header {@code X-LH-Actor} è ignorato e nessun {@code memberId} della richiesta
 * è mai una fonte del membro. Premi, pool e coupon li crea un operatore {@code ADMIN} con il suo token.
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
    private static final String REQUESTED = "io.loyaltyhub.fact.reward.redemption.requested";

    /** Base numerica degli id dei membri di questa classe concreta: due classi sullo stesso contesto non si pestano i piedi. */
    abstract int idBase();

    private String A;
    private String B;
    private String C; // poi anonimizzato
    private String SUB_A;
    private String SUB_B;
    private String SUB_C;
    private String SUB_NEW;
    private String openReward;
    private String limitedReward;
    private String couponOfA;
    private String couponOfB;
    private String redemptionOfA;
    private String redemptionOfB;

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    /** Il broker di questo contesto (la proprietà di sistema è quella dell'ultimo broker avviato nella JVM). */
    @Autowired
    private org.springframework.kafka.test.EmbeddedKafkaBroker embeddedKafka;

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
        registry.add("spring.datasource.url", () -> base + "&currentSchema=reward");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
        registry.add("loyaltyhub.reward.redemption-timeout.enabled", () -> "false");
        registry.add(IdentityGuard.SUBJECT_KEY_PROPERTY, () -> TestSubjectKeys.base64(SUBJECT_KEY));
    }

    /**
     * A, B e C registrati e legati da fatti veri; due premi (uno con limite per membro), un pool con un coupon per A e
     * uno per B, una richiesta premio di A e una di B, per riconoscere i dati di ciascuno.
     */
    @BeforeAll
    void linkMembersAndSeedData() {
        MemberFactsSupport.useBroker(embeddedKafka.getBrokersAsString());
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

        String admin = TOKENS.operator("sara.admin", "ADMIN");
        openReward = liveReward(admin, "RWD-OIDC-" + idBase() + "-OPEN", null);
        limitedReward = liveReward(admin, "RWD-OIDC-" + idBase() + "-LIM", 1);

        String prefix = "OI" + (idBase() / 10_000);
        Reply pool = request(HttpMethod.POST, "/v1/coupon-pools", admin, null, MediaType.APPLICATION_JSON,
                json(Map.of("code", "POOL-OIDC-" + idBase(), "name", "Pool prova", "prefix", prefix, "validityDays", 30)));
        assertThat(pool.status).as(pool.text).isBetween(200, 299);
        String poolId = pool.body.path("id").asString();
        assertThat(request(HttpMethod.POST, "/v1/coupon-pools/" + poolId + "/generate", admin, null, MediaType.APPLICATION_JSON,
                json(Map.of("count", 4))).status).isEqualTo(200);
        List<String> codes = jdbc.sql("SELECT code FROM coupon WHERE pool_id = ? AND status = 'AVAILABLE' ORDER BY code")
                .param(poolId).query(String.class).list();
        couponOfA = codes.get(0);
        couponOfB = codes.get(1);
        issue(couponOfA, A);
        issue(couponOfB, B);

        redemptionOfA = redeem(TOKENS.member(SUB_A), openReward).body.path("redemptionId").asString();
        redeem(TOKENS.member(SUB_A), limitedReward);
        redemptionOfB = redeem(TOKENS.member(SUB_B), openReward).body.path("redemptionId").asString();
    }

    // ---------- il membro dal token: i propri dati, mai quelli altrui ----------

    @Test
    @DisplayName("[TB-RWD-MBP-020] A legge catalogo, premio, coupon e richieste propri: 200, e l'id di B non compare mai")
    void memberReadsOnlyOwnData() {
        String token = TOKENS.member(SUB_A);
        Reply catalog = get("/v1/portal/catalog", token);
        Reply detail = get("/v1/portal/rewards/" + limitedReward, token);
        Reply coupons = get("/v1/portal/coupons", token);
        Reply redemptions = get("/v1/portal/redemptions", token);
        Reply one = get("/v1/portal/redemptions/" + redemptionOfA, token);
        Reply categories = get("/v1/portal/reward-categories", token);

        for (Reply r : List.of(catalog, detail, coupons, redemptions, one, categories)) {
            assertThat(r.status).as(r.text).isEqualTo(200);
            // proprietà generica: nessuna risposta di A contiene l'id di B, l'e-mail o lo username del token
            assertThat(r.text).doesNotContain(B).doesNotContain(OidcTestTokens.emailOf(SUB_A))
                    .doesNotContain(OidcTestTokens.usernameOf(SUB_A));
        }
        // il catalogo è personalizzato: A ha già raggiunto il limite del premio con limite per membro
        assertThat(catalogEntry(catalog.body, limitedReward).path("perMemberLimitReached").asBoolean()).isTrue();
        assertThat(catalogEntry(catalog.body, openReward)).isNotNull();
        assertThat(detail.body.path("perMemberLimitReached").asBoolean()).isTrue();
        assertThat(codesOf(coupons.body)).containsExactly(couponOfA);
        assertThat(StreamSupport.stream(redemptions.body.spliterator(), false).map(n -> n.path("memberId").asString()))
                .isNotEmpty().containsOnly(A);
        assertThat(ids(redemptions.body)).contains(redemptionOfA).doesNotContain(redemptionOfB);
        assertThat(one.body.path("memberId").asString()).isEqualTo(A);

        // B, con il suo token, vede i suoi dati e non quelli di A
        String tokenB = TOKENS.member(SUB_B);
        assertThat(codesOf(get("/v1/portal/coupons", tokenB).body)).containsExactly(couponOfB);
        assertThat(ids(get("/v1/portal/redemptions", tokenB).body)).containsExactly(redemptionOfB);
        assertThat(catalogEntry(get("/v1/portal/catalog", tokenB).body, limitedReward).path("perMemberLimitReached").asBoolean())
                .isFalse();
    }

    @Test
    @DisplayName("[TB-RWD-MBP-021] /v1/portal/reward-categories è la lista di /v1/reward-categories, aperta ai membri; con memberId 400")
    void categoriesAreOpenToMembers() {
        Reply portal = get("/v1/portal/reward-categories", TOKENS.member(SUB_A));
        Reply backoffice = get("/v1/reward-categories", TOKENS.operator("carla", "CARE"));
        assertThat(portal.status).as(portal.text).isEqualTo(200);
        assertThat(backoffice.status).isEqualTo(200);
        assertThat(portal.body).isEqualTo(backoffice.body);
        assertThat(get("/v1/portal/reward-categories?memberId=" + B, TOKENS.member(SUB_A)).status).isEqualTo(400);
        assertThat(get("/v1/portal/reward-categories?memberId=" + A, TOKENS.member(SUB_A)).status).isEqualTo(400);
        // un operatore la legge come le altre letture del portale
        assertThat(get("/v1/portal/reward-categories", TOKENS.operator("carla", "CARE")).status).isEqualTo(200);
    }

    @Test
    @DisplayName("[TB-RWD-MBP-022] memberId in query in qualunque grafia, anche il proprio: 400 MEMBER_FROM_TOKEN")
    void memberIdInTheQueryIsRefused() {
        String token = TOKENS.member(SUB_A);
        long before = redemptionsOf(B);
        for (String path : List.of("/v1/portal/catalog?memberId=" + B, "/v1/portal/catalog?memberId=" + A,
                "/v1/portal/catalog?MEMBERID=" + B, "/v1/portal/catalog?member_id=" + B,
                "/v1/portal/catalog?!memberId=" + B, "/v1/portal/catalog?filter.memberId=" + B,
                "/v1/portal/rewards/" + openReward + "?memberId=" + B, "/v1/portal/coupons?memberId=" + B,
                "/v1/portal/coupons?memberId=" + A, "/v1/portal/redemptions?memberId=" + B,
                "/v1/portal/redemptions?size=3&memberId=" + A, "/v1/portal/redemptions/" + redemptionOfB + "?memberId=" + B)) {
            Reply r = get(path, token);
            assertThat(r.status).as(path + " " + r.text).isEqualTo(400);
            assertThat(r.body.path("code").asString()).as(path).isEqualTo("MEMBER_FROM_TOKEN");
            assertThat(r.text).as(path).doesNotContain(B);
        }
        // l'annullo con memberId nella query è rifiutato prima di toccare la richiesta di B
        Reply cancel = request(HttpMethod.POST, "/v1/portal/redemptions/" + redemptionOfB + "/cancel?memberId=" + B, token, null,
                null, null);
        assertThat(cancel.status).as(cancel.text).isEqualTo(400);
        assertThat(cancel.body.path("code").asString()).isEqualTo("MEMBER_FROM_TOKEN");
        assertThat(statusOf(redemptionOfB)).isEqualTo("PENDING");
        assertThat(redemptionsOf(B)).isEqualTo(before);
    }

    @Test
    @DisplayName("[TB-RWD-MBP-023] memberId come campo form sulla richiesta premio: 400 MEMBER_FROM_TOKEN, nessuna riga e nessun fatto")
    void memberIdAsFormFieldIsRefused() {
        String token = TOKENS.member(SUB_A);
        long redemptionsB = redemptionsOf(B);
        long redemptionsA = redemptionsOf(A);
        long facts = requestedFacts();
        for (String form : List.of("memberId=" + B + "&rewardCode=" + openReward, "memberId=" + A + "&rewardCode=" + openReward,
                "MemberId=" + B + "&rewardCode=" + openReward)) {
            Reply r = request(HttpMethod.POST, "/v1/portal/redemptions", token, null, MediaType.APPLICATION_FORM_URLENCODED, form);
            assertThat(r.status).as(form + " " + r.text).isEqualTo(400);
            assertThat(r.body.path("code").asString()).isEqualTo("MEMBER_FROM_TOKEN");
        }
        assertThat(redemptionsOf(B)).isEqualTo(redemptionsB);
        assertThat(redemptionsOf(A)).isEqualTo(redemptionsA);
        assertThat(requestedFacts()).isEqualTo(facts);
    }

    @Test
    @DisplayName("[TB-RWD-MBP-024] memberId nel corpo della richiesta premio (anche annidato, anche il proprio): 400 MEMBER_FROM_TOKEN")
    void memberIdInTheBodyIsRefused() {
        String token = TOKENS.member(SUB_A);
        long redemptionsB = redemptionsOf(B);
        long redemptionsA = redemptionsOf(A);
        long facts = requestedFacts();
        for (Map<String, Object> body : List.of(
                Map.<String, Object>of("memberId", B, "rewardCode", openReward),
                Map.<String, Object>of("memberId", A, "rewardCode", openReward),
                Map.<String, Object>of("rewardCode", openReward, "shipping", Map.of("memberId", B)),
                Map.<String, Object>of("rewardCode", openReward, "shipping", Map.of("a", List.of(Map.of("memberId", B)))))) {
            Reply r = request(HttpMethod.POST, "/v1/portal/redemptions", token, null, MediaType.APPLICATION_JSON, json(body));
            assertThat(r.status).as(body + " " + r.text).isEqualTo(400);
            assertThat(r.body.path("code").asString()).isEqualTo("MEMBER_FROM_TOKEN");
            assertThat(r.text).doesNotContain(B);
        }
        assertThat(redemptionsOf(B)).isEqualTo(redemptionsB);
        assertThat(redemptionsOf(A)).isEqualTo(redemptionsA);
        assertThat(requestedFacts()).isEqualTo(facts);
    }

    @Test
    @DisplayName("[TB-RWD-MBP-025] una grafia del corpo che il DTO non lega è scartata da Jackson: la richiesta è sempre del titolare del token")
    void unboundBodySpellingsNeverChangeTheMember() {
        String token = TOKENS.member(SUB_A);
        long redemptionsB = redemptionsOf(B);
        // SPEC-GAP: Q-573 — MemberBodyAdvice vede solo l'oggetto già deserializzato (docs/06 §3.4); «MEMBER_ID» non è una
        // componente di RedemptionRequest, quindi Jackson lo scarta prima e la richiesta riesce, ma il membro resta
        // quello del token: nessun BOLA. La correzione (rifiuto del corpo grezzo) sta in lh-common.
        Reply r = request(HttpMethod.POST, "/v1/portal/redemptions", token, null, MediaType.APPLICATION_JSON,
                json(Map.of("MEMBER_ID", B, "rewardCode", openReward)));
        assertThat(r.status).as(r.text).isEqualTo(202);
        String id = r.body.path("redemptionId").asString();
        assertThat(jdbc.sql("SELECT member_id FROM redemption WHERE id = ?").param(id).query(String.class).single()).isEqualTo(A);
        assertThat(redemptionsOf(B)).isEqualTo(redemptionsB);
    }

    @Test
    @DisplayName("[TB-RWD-MBP-026] X-LH-Member in oidc: 400 MEMBER_FROM_TOKEN, anche con il proprio id")
    void demoMemberHeaderIsRefused() {
        for (String id : List.of(B, A)) {
            for (String path : List.of("/v1/portal/catalog", "/v1/portal/coupons", "/v1/portal/redemptions",
                    "/v1/portal/redemptions/" + redemptionOfA)) {
                Reply r = request(HttpMethod.GET, path, TOKENS.member(SUB_A), id, null, null);
                assertThat(r.status).as(path + " " + r.text).isEqualTo(400);
                assertThat(r.body.path("code").asString()).isEqualTo("MEMBER_FROM_TOKEN");
            }
        }
    }

    @Test
    @DisplayName("[TB-RWD-MBP-027] le richieste di B viste da A: 404, e lo stato di B non cambia; l'annullo del titolare funziona")
    void anotherMembersObjectsAreNotFound() {
        String token = TOKENS.member(SUB_A);
        Reply read = get("/v1/portal/redemptions/" + redemptionOfB, token);
        assertThat(read.status).as(read.text).isEqualTo(404);
        assertThat(read.body.path("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(read.text).doesNotContain(B);
        // stessa risposta per una richiesta che non esiste: l'esistere di quella altrui non si rivela
        assertThat(get("/v1/portal/redemptions/RDM-INESISTENTE", token).status).isEqualTo(404);

        Reply cancel = request(HttpMethod.POST, "/v1/portal/redemptions/" + redemptionOfB + "/cancel", token, null, null, null);
        assertThat(cancel.status).as(cancel.text).isEqualTo(404);
        assertThat(statusOf(redemptionOfB)).isEqualTo("PENDING");
        assertThat(historyActors(redemptionOfB)).doesNotContain("member:" + A);

        // il titolare annulla la propria richiesta ancora in attesa
        String mine = redeem(token, openReward).body.path("redemptionId").asString();
        Reply own = request(HttpMethod.POST, "/v1/portal/redemptions/" + mine + "/cancel", token, null, null, null);
        assertThat(own.status).as(own.text).isEqualTo(200);
        assertThat(own.body.path("status").asString()).isEqualTo("CANCELLED");
        assertThat(own.body.path("memberId").asString()).isEqualTo(A);
    }

    // ---------- operatori, token misti, fonti ----------

    @Test
    @DisplayName("[TB-RWD-MBP-028] un operatore CARE non agisce come membro: 403 MEMBER_REQUIRED sulle funzioni del membro, vista generica sul catalogo")
    void operatorIsNotAMember() {
        String token = TOKENS.operator("carla", "CARE");
        long before = redemptionsOf(A);
        for (String path : List.of("/v1/portal/coupons", "/v1/portal/redemptions", "/v1/portal/redemptions/" + redemptionOfA)) {
            Reply r = get(path, token);
            assertThat(r.status).as(path + " " + r.text).isEqualTo(403);
            assertThat(r.body.path("code").asString()).isEqualTo("MEMBER_REQUIRED");
            assertThat(r.text).doesNotContain(A);
        }
        Reply post = request(HttpMethod.POST, "/v1/portal/redemptions", token, null, MediaType.APPLICATION_JSON,
                json(Map.of("rewardCode", openReward)));
        assertThat(post.status).as(post.text).isEqualTo(403);
        assertThat(post.body.path("code").asString()).isEqualTo("MEMBER_REQUIRED");
        Reply cancel = request(HttpMethod.POST, "/v1/portal/redemptions/" + redemptionOfA + "/cancel", token, null, null, null);
        assertThat(cancel.status).as(cancel.text).isEqualTo(403);
        assertThat(cancel.body.path("code").asString()).isEqualTo("MEMBER_REQUIRED");
        assertThat(statusOf(redemptionOfA)).isEqualTo("PENDING");
        assertThat(redemptionsOf(A)).isEqualTo(before);

        // OPTIONAL: la vista generica (BO-17 legge il catalogo), senza limiti per membro né livello
        assertGenericCatalog(token);
    }

    @Test
    @DisplayName("[TB-RWD-MBP-029] token misto MEMBER+CARE: vale come operatore, 403 sulle funzioni del membro, vista generica sul catalogo")
    void mixedTokenIsNotAMember() {
        String token = TOKENS.mixed(SUB_A, "CARE");
        Reply r = get("/v1/portal/coupons", token);
        assertThat(r.status).as(r.text).isEqualTo(403);
        assertThat(r.body.path("code").asString()).isEqualTo("MEMBER_REQUIRED");
        assertThat(r.text).doesNotContain(A);
        assertThat(get("/v1/portal/redemptions", token).status).isEqualTo(403);
        assertGenericCatalog(token);
    }

    @Test
    @DisplayName("[TB-RWD-MBP-030] MEMBER+SOURCE: 403 sul portale, mai membro")
    void memberPlusSourceIsForbidden() {
        String token = TOKENS.memberSource(SUB_A, "src-ecommerce");
        for (String path : List.of("/v1/portal/catalog", "/v1/portal/rewards/" + openReward, "/v1/portal/coupons",
                "/v1/portal/redemptions", "/v1/portal/reward-categories")) {
            Reply r = get(path, token);
            assertThat(r.status).as(path + " " + r.text).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("[TB-RWD-MBP-031] un token di membro sulle API di backoffice (R5 senza members) e sulle scritture: 403 FORBIDDEN_ROLE")
    void memberTokenCannotReachBackoffice() {
        String token = TOKENS.member(SUB_A);
        for (String path : List.of("/v1/rewards", "/v1/redemptions", "/v1/redemptions/" + redemptionOfB, "/v1/reward-categories",
                "/v1/reward-bands", "/v1/coupon-pools", "/v1/stats/rewards")) {
            Reply r = get(path, token);
            assertThat(r.status).as(path + " " + r.text).isEqualTo(403);
            assertThat(r.body.path("code").asString()).as(path).isEqualTo("FORBIDDEN_ROLE");
            assertThat(r.text).as(path).doesNotContain(B);
        }
        Reply create = request(HttpMethod.POST, "/v1/rewards", token, null, MediaType.APPLICATION_JSON,
                json(Map.of("code", "RWD-INTRUSO", "name", "Intruso", "type", "DIGITAL", "band", "F1", "fulfilment", "INSTANT")));
        assertThat(create.status).as(create.text).isEqualTo(403);
        Reply fulfil = request(HttpMethod.POST, "/v1/redemptions/" + redemptionOfB + "/fulfil", token, null,
                MediaType.APPLICATION_JSON, json(Map.of("note", "x")));
        assertThat(fulfil.status).as(fulfil.text).isEqualTo(403);
        assertThat(statusOf(redemptionOfB)).isEqualTo("PENDING");
        assertThat(jdbc.sql("SELECT count(*) FROM reward WHERE code = 'RWD-INTRUSO'").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("[TB-RWD-MBP-032] X-LH-Actor è ignorato con un token: il membro resta membro")
    void actorHeaderIsIgnored() {
        Reply backoffice = request(HttpMethod.GET, "/v1/redemptions/" + redemptionOfB, TOKENS.member(SUB_A), null, null, null,
                "ADMIN:intruso");
        assertThat(backoffice.status).as(backoffice.text).isEqualTo(403);
        assertThat(backoffice.body.path("code").asString()).isEqualTo("FORBIDDEN_ROLE");
        Reply portal = request(HttpMethod.GET, "/v1/portal/redemptions", TOKENS.member(SUB_A), null, null, null, "CARE:intruso");
        assertThat(portal.status).as(portal.text).isEqualTo(200);
        assertThat(ids(portal.body)).contains(redemptionOfA).doesNotContain(redemptionOfB);
    }

    // ---------- legame assente, anonimizzato, token non validi ----------

    @Test
    @DisplayName("[TB-RWD-MBP-033] un sub non ancora legato: 409 MEMBER_NOT_LINKED con Retry-After (catalogo generico), poi 200 appena arriva il fatto")
    void unlinkedSubjectGets409UntilTheFactArrives() {
        String token = TOKENS.member(SUB_NEW);
        Reply r = get("/v1/portal/coupons", token);
        assertThat(r.status).as(r.text).isEqualTo(409);
        assertThat(r.body.path("code").asString()).isEqualTo("MEMBER_NOT_LINKED");
        assertThat(r.retryAfter).isEqualTo("2");
        assertThat(get("/v1/portal/redemptions", token).status).isEqualTo(409);
        Reply post = request(HttpMethod.POST, "/v1/portal/redemptions", token, null, MediaType.APPLICATION_JSON,
                json(Map.of("rewardCode", openReward)));
        assertThat(post.status).as(post.text).isEqualTo(409);
        assertThat(post.retryAfter).isEqualTo("2");
        // OPTIONAL: senza legame il catalogo è quello generico, non un errore
        assertThat(get("/v1/portal/catalog", token).status).isEqualTo(200);

        String member = String.format("MBR-%06d", idBase() + 4);
        awaitCommitted(MemberFactsSupport.registered(member, ref(SUB_NEW), "2026-09-02T10:00:00Z", 2));
        Reply linked = get("/v1/portal/redemptions", token);
        assertThat(linked.status).as(linked.text).isEqualTo(200);
        assertThat(linked.body.isEmpty()).isTrue();
        Reply made = redeem(token, openReward);
        assertThat(made.status).as(made.text).isEqualTo(202);
        assertThat(jdbc.sql("SELECT member_id FROM redemption WHERE id = ?").param(made.body.path("redemptionId").asString())
                .query(String.class).single()).isEqualTo(member);
    }

    @Test
    @DisplayName("[TB-RWD-MBP-034] C anonimizzato: 409 e il replay del fatto di registrazione non ri-lega")
    void anonymizedMemberIsUnlinkedForGood() {
        String token = TOKENS.member(SUB_C);
        assertThat(get("/v1/portal/coupons", token).status).isEqualTo(200);

        awaitCommitted(MemberFactsSupport.statusChanged(C, "ANONYMIZED", "2026-09-03T10:00:00Z"));
        Reply gone = get("/v1/portal/coupons", token);
        assertThat(gone.status).as(gone.text).isEqualTo(409);
        assertThat(gone.body.path("code").asString()).isEqualTo("MEMBER_NOT_LINKED");
        assertThat(gone.retryAfter).isEqualTo("2");

        // replay del fatto di registrazione e un member.updated più recente: la lapide vince
        awaitCommitted(MemberFactsSupport.registered(C, ref(SUB_C), "2026-09-01T10:00:00Z", 2),
                MemberFactsSupport.updated(C, ref(SUB_C), "2026-09-05T10:00:00Z", 2));
        assertThat(get("/v1/portal/coupons", token).status).isEqualTo(409);
        assertThat(get("/v1/portal/redemptions", token).status).isEqualTo(409);
    }

    @Test
    @DisplayName("[TB-RWD-MBP-035] token assente, scaduto, firmato con un'altra chiave, audience o emittente sbagliati: 401")
    void invalidTokensAreUnauthorized() {
        for (String path : List.of("/v1/portal/coupons", "/v1/portal/catalog", "/v1/portal/reward-categories")) {
            assertThat(get(path, null).status).as(path).isEqualTo(401);
            assertThat(get(path, TOKENS.expired(SUB_A)).status).as(path).isEqualTo(401);
            assertThat(get(path, TOKENS.foreignKey(SUB_A)).status).as(path).isEqualTo(401);
            assertThat(get(path, TOKENS.wrongAudience(SUB_A)).status).as(path).isEqualTo(401);
            assertThat(get(path, TOKENS.wrongIssuer(SUB_A)).status).as(path).isEqualTo(401);
        }
    }

    // ---------- scritture: attore member:<id>, nessun dato personale ----------

    @Test
    @DisplayName("[TB-RWD-MBP-036] richiesta e annullo del membro portano l'attore member:<id> e nessun nome utente né e-mail")
    void writesCarryTheMemberActorWithoutPii() {
        String token = TOKENS.member(SUB_A);
        Reply made = redeem(token, openReward);
        assertThat(made.status).as(made.text).isEqualTo(202);
        String id = made.body.path("redemptionId").asString();
        Reply cancel = request(HttpMethod.POST, "/v1/portal/redemptions/" + id + "/cancel", token, null, null, null);
        assertThat(cancel.status).as(cancel.text).isEqualTo(200);

        String actor = "member:" + A;
        assertThat(jdbc.sql("SELECT actor FROM redemption WHERE id = ?").param(id).query(String.class).single()).isEqualTo(actor);
        assertThat(historyActors(id)).isNotEmpty().containsOnly(actor);
        List<JsonNode> facts = jdbc.sql("SELECT payload::text FROM outbox WHERE topic = 'lh.facts.v1' AND payload->'data'->>'redemptionId' = ?")
                .param(id).query(String.class).list().stream().map(mapper::readTree).toList();
        assertThat(facts).extracting(f -> f.path("type").asString())
                .contains(REQUESTED, "io.loyaltyhub.fact.reward.redemption.cancelled");
        assertThat(facts).extracting(f -> f.path("lhactor").asString()).containsOnly(actor);
        // nessun dato del token (nome utente, e-mail, sub) nelle scritture della richiesta
        String stored = jdbc.sql("SELECT string_agg(t, ' ') FROM (SELECT payload::text AS t FROM outbox WHERE payload->'data'->>'redemptionId' = ? "
                + "UNION ALL SELECT actor FROM redemption_history WHERE redemption_id = ? "
                + "UNION ALL SELECT actor FROM redemption WHERE id = ?) x").params(id, id, id).query(String.class).single();
        assertThat(stored).doesNotContain(OidcTestTokens.usernameOf(SUB_A)).doesNotContain(OidcTestTokens.emailOf(SUB_A))
                .doesNotContain(SUB_A);
    }

    // ---------- supporto ----------

    private void assertGenericCatalog(String operatorToken) {
        Reply catalog = get("/v1/portal/catalog", operatorToken);
        assertThat(catalog.status).as(catalog.text).isEqualTo(200);
        assertThat(catalogEntry(catalog.body, openReward)).isNotNull();
        // vista generica: nessun limite per membro raggiunto (A lo ha raggiunto con il suo token)
        assertThat(catalogEntry(catalog.body, limitedReward).path("perMemberLimitReached").asBoolean()).isFalse();
        Reply detail = get("/v1/portal/rewards/" + limitedReward, operatorToken);
        assertThat(detail.status).as(detail.text).isEqualTo(200);
        assertThat(detail.body.path("perMemberLimitReached").asBoolean()).isFalse();
        assertThat(catalog.text).doesNotContain(A).doesNotContain(B);
    }

    private String liveReward(String admin, String code, Integer perMemberLimit) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("code", code);
        body.put("name", "Premio di prova " + code);
        body.put("type", "DIGITAL");
        body.put("band", "F1");
        body.put("category", "TEMPO");
        body.put("fulfilment", "INSTANT");
        body.put("stockTotal", 100);
        if (perMemberLimit != null) {
            body.put("perMemberLimit", perMemberLimit);
        }
        Reply created = request(HttpMethod.POST, "/v1/rewards", admin, null, MediaType.APPLICATION_JSON, json(body));
        assertThat(created.status).as(created.text).isEqualTo(201);
        String id = created.body.path("id").asString();
        for (String action : List.of("SUBMIT", "APPROVE", "PUBLISH")) {
            Reply t = request(HttpMethod.POST, "/v1/rewards/" + id + "/transitions", admin, null, MediaType.APPLICATION_JSON,
                    json(Map.of("action", action, "comment", "prova")));
            assertThat(t.status).as(action + " " + t.text).isEqualTo(200);
        }
        return code;
    }

    private void issue(String couponCode, String memberId) {
        jdbc.sql("UPDATE coupon SET status = 'ISSUED', member_id = ?, reward_code = ?, origin = 'REDEMPTION', "
                + "issued_at = now(), expires_at = now() + interval '30 days' WHERE code = ?")
                .params(memberId, openReward, couponCode).update();
    }

    private Reply redeem(String token, String rewardCode) {
        Reply r = request(HttpMethod.POST, "/v1/portal/redemptions", token, null, MediaType.APPLICATION_JSON,
                json(Map.of("rewardCode", rewardCode)));
        if (r.status != 409) { // 409: sub non ancora legato (solo il test che lo prova)
            assertThat(r.status).as(r.text).isEqualTo(202);
        }
        return r;
    }

    private JsonNode catalogEntry(JsonNode catalog, String code) {
        for (JsonNode band : catalog.path("bands")) {
            for (JsonNode reward : band.path("rewards")) {
                if (code.equals(reward.path("code").asString())) {
                    return reward;
                }
            }
        }
        return null;
    }

    private static List<String> codesOf(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(n -> n.path("code").asString()).toList();
    }

    private static List<String> ids(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(n -> n.path("id").asString()).toList();
    }

    private long redemptionsOf(String memberId) {
        return jdbc.sql("SELECT count(*) FROM redemption WHERE member_id = ?").param(memberId).query(Long.class).single();
    }

    private long requestedFacts() {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE type = ?").param(REQUESTED).query(Long.class).single();
    }

    private String statusOf(String redemptionId) {
        return jdbc.sql("SELECT status FROM redemption WHERE id = ?").param(redemptionId).query(String.class).single();
    }

    private List<String> historyActors(String redemptionId) {
        return jdbc.sql("SELECT actor FROM redemption_history WHERE redemption_id = ?").param(redemptionId).query(String.class).list();
    }

    private String json(Object body) {
        return mapper.writeValueAsString(body);
    }

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
