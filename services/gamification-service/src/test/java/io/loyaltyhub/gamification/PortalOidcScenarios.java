package io.loyaltyhub.gamification;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Il portale del gioco con il membro solo dal token (F2-SEC-09, ADR-048, Q-410, Q-493, Q-553, Q-554, Q-556, Q-559;
 * docs/06 §3.4 e §9): token OIDC veri firmati RS256 ({@link OidcTestTokens}), verificati da {@code OidcActorFilter} con
 * gli stessi validatori dell'avvio; i membri A, B, C e D legati da fatti veri {@code member.registered} sul bus embedded
 * (proiezione {@code subjectRef → membro}). Profilo {@code enterprise} in miniatura: {@code identity.mode=oidc}, l'header
 * {@code X-LH-Actor} è ignorato e nessun {@code memberId} della richiesta è mai una fonte del membro. A e B hanno
 * punteggi e badge diversi, per riconoscere i dati di ciascuno; il concorso è {@code IW-AUTUNNO} del seed.
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
    private static final String CONTEST = "IW-AUTUNNO";
    private static final String BOARD = "LDB-MONTH-PTS";
    private static final List<String> ID_VARIANTS = List.of("memberId=%s", "MEMBERID=%s", "member_id=%s", "!memberId=%s",
            "filter.memberId=%s");

    /** Base numerica degli id dei membri di questa classe concreta: due classi sullo stesso contesto non si pestano i piedi. */
    abstract int idBase();

    private String A;
    private String B;
    private String C; // poi anonimizzato
    private String D; // per il corpo con grafie che il DTO scarta
    private String SUB_A;
    private String SUB_B;
    private String SUB_C;
    private String SUB_D;
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
        registry.add("spring.datasource.url", () -> base + "&currentSchema=gamification");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
        registry.add(IdentityGuard.SUBJECT_KEY_PROPERTY, () -> TestSubjectKeys.base64(SUBJECT_KEY));
    }

    /**
     * A, B, C e D registrati e legati da fatti veri; A e B con punteggi molto sopra il seed (primi e secondi in classifica)
     * e B con un badge che A non ha, per riconoscere i dati di ciascuno.
     */
    @BeforeAll
    void linkMembers() {
        A = String.format("MBR-%06d", idBase() + 1);
        B = String.format("MBR-%06d", idBase() + 2);
        C = String.format("MBR-%06d", idBase() + 3);
        D = String.format("MBR-%06d", idBase() + 5);
        SUB_A = "sub-utente-a-" + idBase();
        SUB_B = "sub-utente-b-" + idBase();
        SUB_C = "sub-utente-c-" + idBase();
        SUB_D = "sub-utente-d-" + idBase();
        SUB_NEW = "sub-utente-non-legato-" + idBase();
        ListenerGroups.awaitStable(listeners);
        awaitCommitted(
                MemberFactsSupport.registered(A, ref(SUB_A), "2026-09-01T10:00:00Z", 2),
                MemberFactsSupport.registered(B, ref(SUB_B), "2026-09-01T10:00:00Z", 1),
                MemberFactsSupport.registered(C, ref(SUB_C), "2026-09-01T10:00:00Z", 2),
                MemberFactsSupport.registered(D, ref(SUB_D), "2026-09-01T10:00:00Z", 2));
        awaitCommitted(MemberFactsSupport.earned(A, scoreA()), MemberFactsSupport.earned(B, scoreB()),
                MemberFactsSupport.badge(B, "EFF-OIDC-B-" + idBase(), "BDG-FIRST"));
    }

    /**
     * Punteggi di A e B: molto sopra il seed e distinti per classe concreta (due classi condividono classifica e database),
     * quindi le voci si riconoscono dal punteggio e non dalla posizione assoluta.
     */
    private long scoreA() {
        return 900_000L + idBase();
    }

    private long scoreB() {
        return 800_000L + idBase();
    }

    private static JsonNode entryWithScore(JsonNode top, long score) {
        for (JsonNode e : top) {
            if (e.path("score").asLong() == score) {
                return e;
            }
        }
        throw new AssertionError("voce con punteggio " + score + " assente in " + top);
    }

    // ---------- il membro dal token: i propri dati, mai quelli altrui ----------

    @Test
    @DisplayName("[TB-GAM-MBP-020] A legge obiettivi, badge, concorsi, storico e classifiche: 200, dati suoi, mai l'id di B")
    void memberReadsOnlyOwnData() {
        String token = TOKENS.member(SUB_A);
        Reply achievements = get("/v1/portal/achievements", token);
        Reply badges = get("/v1/portal/badges", token);
        Reply contests = get("/v1/portal/contests", token);
        Reply plays = get("/v1/portal/contests/" + CONTEST + "/plays", token);
        Reply boards = get("/v1/portal/leaderboards", token);
        Reply board = get("/v1/portal/leaderboards/" + BOARD, token);
        for (Reply r : List.of(achievements, badges, contests, plays, boards, board)) {
            assertThat(r.status).as(r.text).isEqualTo(200);
            // proprietà generica: nessuna risposta di A contiene l'id di B, l'e-mail o lo username del token
            assertThat(r.text).doesNotContain(B).doesNotContain(OidcTestTokens.emailOf(SUB_A))
                    .doesNotContain(OidcTestTokens.usernameOf(SUB_A)).doesNotContain(OidcTestTokens.emailOf(SUB_B));
        }
        assertThat(achievements.body.isArray()).isTrue();
        // il badge BDG-FIRST è di B, non di A
        assertThat(badge(badges.body, "BDG-FIRST").hasNonNull("awardedAt")).as("A non ha BDG-FIRST").isFalse();
        assertThat(badge(get("/v1/portal/badges", TOKENS.member(SUB_B)).body, "BDG-FIRST").hasNonNull("awardedAt"))
                .as("B ha BDG-FIRST").isTrue();
        // il concorso in corso e la posizione in classifica sono quelli di A
        assertThat(contests.body.get(0).path("code").asString()).isEqualTo(CONTEST);
        assertThat(board.body.path("me").path("score").asLong()).isEqualTo(scoreA());
        assertThat(board.body.path("me").path("rank").asInt()).as("solo l'A dell'altra classe può stare davanti").isBetween(1, 2);
        assertThat(get("/v1/portal/leaderboards/" + BOARD, TOKENS.member(SUB_B)).body.path("me").path("score").asLong())
                .isEqualTo(scoreB());
        assertThat(boards.body.get(0).path("me").path("score").asLong()).isPositive();
    }

    @Test
    @DisplayName("[TB-GAM-MBP-021] isMe viene dal principal: nella stessa classifica è vero per A a chi è A e per B a chi è B")
    void isMeComesFromThePrincipal() {
        JsonNode forA = get("/v1/portal/leaderboards/" + BOARD, TOKENS.member(SUB_A)).body.path("top");
        JsonNode forB = get("/v1/portal/leaderboards/" + BOARD, TOKENS.member(SUB_B)).body.path("top");
        assertThat(entryWithScore(forA, scoreA()).path("isMe").asBoolean()).isTrue();
        assertThat(entryWithScore(forA, scoreB()).path("isMe").asBoolean()).isFalse();
        assertThat(entryWithScore(forB, scoreA()).path("isMe").asBoolean()).isFalse();
        assertThat(entryWithScore(forB, scoreB()).path("isMe").asBoolean()).isTrue();
        assertThat(forA.findValues("isMe").stream().filter(JsonNode::asBoolean).count()).as("una sola voce è di A").isEqualTo(1);
        // un membro senza punteggio non compare: me assente, nessun isMe vero
        JsonNode forD = get("/v1/portal/leaderboards/" + BOARD, TOKENS.member(SUB_D)).body;
        assertThat(forD.path("me").isNull() || forD.path("me").isMissingNode()).isTrue();
        for (JsonNode e : forD.path("top")) {
            assertThat(e.path("isMe").asBoolean()).isFalse();
        }
    }

    @Test
    @DisplayName("[TB-GAM-MBP-022] resolve=ids è ignorato per un token (Q-559): mai i memberId altrui, soprannomi o segnaposto")
    void resolveIdsIsIgnoredForATokenPrincipal() {
        String token = TOKENS.member(SUB_A);
        Reply one = get("/v1/portal/leaderboards/" + BOARD + "?resolve=ids", token);
        Reply all = get("/v1/portal/leaderboards?resolve=ids", token);
        for (Reply r : List.of(one, all)) {
            assertThat(r.status).as(r.text).isEqualTo(200);
            assertThat(r.text).doesNotContain("memberId").doesNotContain("MBR-").doesNotContain(B);
        }
        for (JsonNode e : one.body.path("top")) {
            assertThat(e.hasNonNull("nickname")).as("soprannome dello snapshot o segnaposto").isTrue();
        }
        assertThat(entryWithScore(one.body.path("top"), scoreA()).path("isMe").asBoolean()).isTrue();
        // un valore diverso da ids resta un errore del client
        assertThat(get("/v1/portal/leaderboards/" + BOARD + "?resolve=nicknames", token).status).isEqualTo(400);
    }

    @Test
    @DisplayName("[TB-GAM-MBP-023] A gioca: 200 sul proprio conto, storico suo, lhactor member:<id> senza dati personali")
    void playIsAttributedToTheTokenMember() {
        String token = TOKENS.member(SUB_A);
        Reply played = post("/v1/portal/contests/" + CONTEST + "/play", token, Map.of());
        assertThat(played.status).as(played.text).isEqualTo(200);
        String playId = played.body.path("playId").asString();
        assertThat(playId).isNotBlank();
        assertThat(jdbc.sql("SELECT member_id FROM play WHERE id = ?").param(playId).query(String.class).single()).isEqualTo(A);

        // la gratuita di oggi è usata: la seconda giocata di A è rifiutata come per un membro qualunque
        Reply again = post("/v1/portal/contests/" + CONTEST + "/play", token, Map.of());
        assertThat(again.status).isEqualTo(422);
        assertThat(again.body.path("code").asString()).isEqualTo("NO_PLAYS_AVAILABLE");

        // B gioca dal proprio token; lo storico di A contiene la giocata di A e non quella di B
        Reply playedB = post("/v1/portal/contests/" + CONTEST + "/play", TOKENS.member(SUB_B), Map.of());
        assertThat(playedB.status).as(playedB.text).isEqualTo(200);
        String playIdB = playedB.body.path("playId").asString();
        Reply history = get("/v1/portal/contests/" + CONTEST + "/plays", token);
        assertThat(history.status).isEqualTo(200);
        assertThat(history.text).contains(playId).doesNotContain(playIdB).doesNotContain(B);

        // scrittura attribuibile senza PII: l'attore del fatto è member:<id>, mai lo username o l'e-mail del token
        List<String> actors = jdbc.sql("SELECT payload ->> 'lhactor' FROM outbox WHERE type = 'io.loyaltyhub.fact.contest.played'"
                        + " AND payload ->> 'subject' = ?").param("member:" + A).query(String.class).list();
        assertThat(actors).contains("member:" + A).doesNotContain("MEMBER:" + A);
        String payload = jdbc.sql("SELECT payload::text FROM outbox WHERE payload -> 'data' ->> 'playId' = ?").param(playId)
                .query(String.class).list().toString();
        assertThat(payload).doesNotContain(OidcTestTokens.usernameOf(SUB_A)).doesNotContain(OidcTestTokens.emailOf(SUB_A))
                .doesNotContain(SUB_A);
    }

    // ---------- il membro non è mai un parametro ----------

    @Test
    @DisplayName("[TB-GAM-MBP-024] memberId in query in qualunque grafia, anche il proprio: 400 MEMBER_FROM_TOKEN su ogni lettura")
    void memberIdInTheQueryIsRefused() {
        String token = TOKENS.member(SUB_A);
        for (String base : List.of("/v1/portal/achievements", "/v1/portal/badges", "/v1/portal/contests",
                "/v1/portal/contests/" + CONTEST + "/plays", "/v1/portal/leaderboards", "/v1/portal/leaderboards/" + BOARD)) {
            for (String variant : ID_VARIANTS) {
                for (String id : List.of(B, A)) {
                    String path = base + "?" + variant.formatted(id);
                    Reply r = get(path, token);
                    assertThat(r.status).as(path + " " + r.text).isEqualTo(400);
                    assertThat(r.body.path("code").asString()).as(path).isEqualTo("MEMBER_FROM_TOKEN");
                    assertThat(r.text).as(path).doesNotContain(B);
                }
            }
        }
        // anche accanto a un parametro legittimo
        Reply r = get("/v1/portal/leaderboards?resolve=ids&memberId=" + B, token);
        assertThat(r.status).isEqualTo(400);
        assertThat(r.body.path("code").asString()).isEqualTo("MEMBER_FROM_TOKEN");
    }

    @Test
    @DisplayName("[TB-GAM-MBP-025] memberId come campo form o nel corpo JSON: 400 MEMBER_FROM_TOKEN, nessuna giocata né fatto")
    void memberIdInTheWriteIsRefused() {
        String token = TOKENS.member(SUB_D);
        String path = "/v1/portal/contests/" + CONTEST + "/play";
        long plays = count("SELECT count(*) FROM play");
        long outbox = count("SELECT count(*) FROM outbox");

        for (String id : List.of(B, D)) {
            Reply form = request(HttpMethod.POST, path, token, null, MediaType.APPLICATION_FORM_URLENCODED, "memberId=" + id);
            assertThat(form.status).as("form " + form.text).isEqualTo(400);
            assertThat(form.body.path("code").asString()).isEqualTo("MEMBER_FROM_TOKEN");
            Reply json = post(path, token, Map.of("memberId", id));
            assertThat(json.status).as("json " + json.text).isEqualTo(400);
            assertThat(json.body.path("code").asString()).isEqualTo("MEMBER_FROM_TOKEN");
            assertThat(json.text).doesNotContain(B);
        }
        assertThat(count("SELECT count(*) FROM play")).as("nessuna giocata").isEqualTo(plays);
        assertThat(count("SELECT count(*) FROM outbox")).as("nessun fatto").isEqualTo(outbox);
    }

    @Test
    @DisplayName("[TB-GAM-MBP-026] scostamento noto (Q-573): un corpo con una grafia che il DTO scarta non porta mai un membro")
    void bodySpellingsTheDtoDiscardsNeverCarryAMember() {
        // MemberBodyAdvice vede solo l'oggetto già deserializzato: {"member_id": B} è scartato da Jackson e non rifiutato.
        // Il membro resta quello del token (SPEC-GAP: Q-573): la giocata è di D, mai di B, e nessuna riga è di B.
        String path = "/v1/portal/contests/" + CONTEST + "/play";
        long playsOfB = playsOf(B);
        Reply r = post(path, TOKENS.member(SUB_D), Map.of("member_id", B, "MEMBERID", B));
        assertThat(r.status).as(r.text).isEqualTo(200);
        assertThat(r.text).doesNotContain(B);
        assertThat(jdbc.sql("SELECT member_id FROM play WHERE id = ?").param(r.body.path("playId").asString())
                .query(String.class).single()).isEqualTo(D);
        assertThat(playsOf(B)).isEqualTo(playsOfB);
    }

    @Test
    @DisplayName("[TB-GAM-MBP-027] X-LH-Member in oidc: 400 MEMBER_FROM_TOKEN, anche con il proprio id, su letture e giocata")
    void demoMemberHeaderIsRefused() {
        for (String id : List.of(B, A)) {
            Reply read = request(HttpMethod.GET, "/v1/portal/achievements", TOKENS.member(SUB_A), id, null, null);
            assertThat(read.status).as(read.text).isEqualTo(400);
            assertThat(read.body.path("code").asString()).isEqualTo("MEMBER_FROM_TOKEN");
            Reply write = request(HttpMethod.POST, "/v1/portal/contests/" + CONTEST + "/play", TOKENS.member(SUB_A), id,
                    MediaType.APPLICATION_JSON, "{}");
            assertThat(write.status).as(write.text).isEqualTo(400);
            assertThat(write.body.path("code").asString()).isEqualTo("MEMBER_FROM_TOKEN");
        }
    }

    // ---------- operatori, token misti, fonti ----------

    @Test
    @DisplayName("[TB-GAM-MBP-028] un operatore CARE non agisce come membro: 403 MEMBER_REQUIRED su ogni funzione del portale")
    void operatorIsNotAMember() {
        String token = TOKENS.operator("carla", "CARE");
        for (String path : portalReads()) {
            Reply r = get(path, token);
            assertThat(r.status).as(path + " " + r.text).isEqualTo(403);
            assertThat(r.body.path("code").asString()).isEqualTo("MEMBER_REQUIRED");
        }
        long plays = count("SELECT count(*) FROM play");
        Reply write = post("/v1/portal/contests/" + CONTEST + "/play", token, Map.of());
        assertThat(write.status).isEqualTo(403);
        assertThat(write.body.path("code").asString()).isEqualTo("MEMBER_REQUIRED");
        assertThat(count("SELECT count(*) FROM play")).isEqualTo(plays);
    }

    @Test
    @DisplayName("[TB-GAM-MBP-029] token misto MEMBER+CARE: vale come operatore, 403 sulle funzioni del membro")
    void mixedTokenIsNotAMember() {
        String token = TOKENS.mixed(SUB_A, "CARE");
        for (String path : portalReads()) {
            Reply r = get(path, token);
            assertThat(r.status).as(path + " " + r.text).isEqualTo(403);
            assertThat(r.body.path("code").asString()).isEqualTo("MEMBER_REQUIRED");
            assertThat(r.text).doesNotContain(A);
        }
        assertThat(post("/v1/portal/contests/" + CONTEST + "/play", token, Map.of()).status).isEqualTo(403);
    }

    @Test
    @DisplayName("[TB-GAM-MBP-030] MEMBER+SOURCE: 403 sul portale, mai membro")
    void memberPlusSourceIsForbidden() {
        String token = TOKENS.memberSource(SUB_A, "src-ecommerce");
        for (String path : portalReads()) {
            Reply r = get(path, token);
            assertThat(r.status).as(path + " " + r.text).isEqualTo(403);
        }
        assertThat(post("/v1/portal/contests/" + CONTEST + "/play", token, Map.of()).status).isEqualTo(403);
    }

    @Test
    @DisplayName("[TB-GAM-MBP-031] un token di membro sulle API di backoffice (R5 senza members): 403 FORBIDDEN_ROLE")
    void memberTokenCannotReachBackofficeReads() {
        String token = TOKENS.member(SUB_A);
        for (String path : List.of("/v1/contests", "/v1/contests/" + CONTEST + "/winners", "/v1/achievements", "/v1/badges",
                "/v1/leaderboards", "/v1/leaderboards/" + BOARD + "/ranking")) {
            Reply r = get(path, token);
            assertThat(r.status).as(path + " " + r.text).isEqualTo(403);
            assertThat(r.body.path("code").asString()).as(path).isEqualTo("FORBIDDEN_ROLE");
            assertThat(r.text).as(path).doesNotContain("memberId");
        }
    }

    @Test
    @DisplayName("[TB-GAM-MBP-032] X-LH-Actor è ignorato con un token: il membro resta membro")
    void actorHeaderIsIgnored() {
        Reply backoffice = request(HttpMethod.GET, "/v1/contests", TOKENS.member(SUB_A), null, null, null, "ADMIN:intruso");
        assertThat(backoffice.status).as(backoffice.text).isEqualTo(403);
        assertThat(backoffice.body.path("code").asString()).isEqualTo("FORBIDDEN_ROLE");
        Reply portal = request(HttpMethod.GET, "/v1/portal/leaderboards/" + BOARD, TOKENS.member(SUB_A), null, null, null,
                "ADMIN:intruso");
        assertThat(portal.status).as(portal.text).isEqualTo(200);
        assertThat(portal.body.path("me").path("score").asLong()).isEqualTo(scoreA());
    }

    // ---------- legame assente, anonimizzato, token non validi ----------

    @Test
    @DisplayName("[TB-GAM-MBP-033] un sub non ancora legato: 409 MEMBER_NOT_LINKED con Retry-After, poi 200 appena arriva il fatto")
    void unlinkedSubjectGets409UntilTheFactArrives() {
        String token = TOKENS.member(SUB_NEW);
        Reply r = get("/v1/portal/achievements", token);
        assertThat(r.status).as(r.text).isEqualTo(409);
        assertThat(r.body.path("code").asString()).isEqualTo("MEMBER_NOT_LINKED");
        assertThat(r.retryAfter).isEqualTo("2");
        assertThat(get("/v1/portal/contests", token).status).isEqualTo(409);
        assertThat(post("/v1/portal/contests/" + CONTEST + "/play", token, Map.of()).status).isEqualTo(409);

        String member = String.format("MBR-%06d", idBase() + 4);
        awaitCommitted(MemberFactsSupport.registered(member, ref(SUB_NEW), "2026-09-02T10:00:00Z", 2));
        Reply linked = get("/v1/portal/contests", token);
        assertThat(linked.status).as(linked.text).isEqualTo(200);
        Reply played = post("/v1/portal/contests/" + CONTEST + "/play", token, Map.of());
        assertThat(played.status).as(played.text).isEqualTo(200);
        assertThat(jdbc.sql("SELECT member_id FROM play WHERE id = ?").param(played.body.path("playId").asString())
                .query(String.class).single()).isEqualTo(member);
    }

    @Test
    @DisplayName("[TB-GAM-MBP-034] C anonimizzato: 409 e il replay del fatto di registrazione non ri-lega")
    void anonymizedMemberIsUnlinkedForGood() {
        String token = TOKENS.member(SUB_C);
        assertThat(get("/v1/portal/achievements", token).status).isEqualTo(200);

        awaitCommitted(MemberFactsSupport.statusChanged(C, "ANONYMIZED", "2026-09-03T10:00:00Z"));
        Reply gone = get("/v1/portal/achievements", token);
        assertThat(gone.status).as(gone.text).isEqualTo(409);
        assertThat(gone.body.path("code").asString()).isEqualTo("MEMBER_NOT_LINKED");
        assertThat(gone.retryAfter).isEqualTo("2");

        // replay del fatto di registrazione e un member.updated più recente: la lapide vince
        awaitCommitted(MemberFactsSupport.registered(C, ref(SUB_C), "2026-09-01T10:00:00Z", 2),
                MemberFactsSupport.updated(C, ref(SUB_C), "2026-09-05T10:00:00Z", 2));
        assertThat(get("/v1/portal/achievements", token).status).isEqualTo(409);
        assertThat(get("/v1/portal/leaderboards", token).status).isEqualTo(409);
        assertThat(post("/v1/portal/contests/" + CONTEST + "/play", token, Map.of()).status).isEqualTo(409);
    }

    @Test
    @DisplayName("[TB-GAM-MBP-035] token assente, scaduto, firmato con un'altra chiave, audience o emittente sbagliati: 401")
    void invalidTokensAreUnauthorized() {
        assertThat(get("/v1/portal/achievements", null).status).isEqualTo(401);
        assertThat(get("/v1/portal/achievements", TOKENS.expired(SUB_A)).status).isEqualTo(401);
        assertThat(get("/v1/portal/achievements", TOKENS.foreignKey(SUB_A)).status).isEqualTo(401);
        assertThat(get("/v1/portal/badges", TOKENS.wrongAudience(SUB_A)).status).isEqualTo(401);
        assertThat(get("/v1/portal/contests", TOKENS.wrongIssuer(SUB_A)).status).isEqualTo(401);
        assertThat(post("/v1/portal/contests/" + CONTEST + "/play", TOKENS.expired(SUB_A), Map.of()).status).isEqualTo(401);
        assertThat(post("/v1/portal/contests/" + CONTEST + "/play", null, Map.of()).status).isEqualTo(401);
    }

    // ---------- supporto ----------

    private List<String> portalReads() {
        return List.of("/v1/portal/achievements", "/v1/portal/badges", "/v1/portal/contests",
                "/v1/portal/contests/" + CONTEST + "/plays", "/v1/portal/leaderboards", "/v1/portal/leaderboards/" + BOARD);
    }

    private static JsonNode badge(JsonNode badges, String code) {
        for (JsonNode b : badges) {
            if (code.equals(b.path("code").asString())) {
                return b;
            }
        }
        throw new AssertionError("badge assente: " + code);
    }

    private long playsOf(String memberId) {
        return jdbc.sql("SELECT count(*) FROM play WHERE member_id = ?").param(memberId).query(Long.class).single();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private record Reply(int status, String text, JsonNode body, String retryAfter) {
    }

    private Reply get(String path, String token) {
        return request(HttpMethod.GET, path, token, null, null, null);
    }

    private Reply post(String path, String token, Object json) {
        try {
            return request(HttpMethod.POST, path, token, null, MediaType.APPLICATION_JSON, mapper.writeValueAsString(json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
