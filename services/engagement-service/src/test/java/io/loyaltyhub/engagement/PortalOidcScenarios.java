package io.loyaltyhub.engagement;

import io.loyaltyhub.common.config.IdentityGuard;
import io.loyaltyhub.common.identity.SubjectRef;
import io.loyaltyhub.common.kafka.LoyaltyHubProperties;
import io.loyaltyhub.common.web.ActorHolder;
import io.loyaltyhub.common.web.OidcActorFilter;
import io.loyaltyhub.testsupport.ListenerGroups;
import io.loyaltyhub.testsupport.OidcTestTokens;
import io.loyaltyhub.testsupport.TestSubjectKeys;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
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
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Il portale di engagement con il membro solo dal token (F2-SEC-09, ADR-048, Q-410, Q-553, Q-554; docs/06 §3.4 e §9): token
 * OIDC veri firmati RS256 ({@link OidcTestTokens}), verificati da {@code OidcActorFilter} con gli stessi validatori
 * dell'avvio; i membri A e B legati da fatti veri {@code member.registered} sul bus embedded (proiezione
 * {@code subjectRef → membro}); i loro messaggi nascono da fatti veri {@code wallet.points.earned} e dalla regola di
 * benvenuto. Profilo {@code enterprise} in miniatura: {@code identity.mode=oidc}, l'header {@code X-LH-Actor} è ignorato e
 * nessun {@code memberId} della richiesta è mai una fonte del membro.
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
    /** Attore e MDC visti a fine richiesta: {@code metodo percorso -> actor}. */
    private static final Queue<String> ACTORS = new ConcurrentLinkedQueue<>();

    /** Base numerica degli id dei membri di questa classe concreta: due classi sullo stesso contesto non si pestano i piedi. */
    abstract int idBase();

    private String A;
    private String B;
    private String C; // poi anonimizzato
    private String D; // scrive le viste dei pop-up: A e B restano spettatori
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
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=engagement");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
        registry.add(IdentityGuard.SUBJECT_KEY_PROPERTY, () -> TestSubjectKeys.base64(SUBJECT_KEY));
    }

    /** A, B e C registrati e legati da fatti veri; A e B con punti diversi, per riconoscere il messaggio di ciascuno. */
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
        awaitCommitted(MemberFactsSupport.pointsEarned(A, "EV-OIDC-A-" + idBase(), 111),
                MemberFactsSupport.pointsEarned(B, "EV-OIDC-B-" + idBase(), 222));
    }

    // ---------- il membro dal token: i propri dati, mai quelli altrui ----------

    @Test
    @DisplayName("[TB-ENG-MBP-020] A legge la propria inbox, i non letti, i contenuti, il pop-up e il tema: 200, e l'id di B non compare mai")
    void memberReadsOnlyOwnData() {
        String token = TOKENS.member(SUB_A);
        Reply inbox = get("/v1/portal/inbox?size=50", token);
        assertThat(inbox.status).as(inbox.text).isEqualTo(200);
        assertThat(titles(inbox.body)).contains("Hai guadagnato 111 punti").doesNotContain("Hai guadagnato 222 punti");

        Reply unread = get("/v1/portal/inbox/unread-count", token);
        assertThat(unread.status).as(unread.text).isEqualTo(200);
        assertThat(unread.body.path("memberId").asString()).isEqualTo(A);
        assertThat(unread.body.path("unread").asLong()).isEqualTo(unreadOf(inbox.body));

        Reply other = get("/v1/portal/inbox?size=50", TOKENS.member(SUB_B));
        assertThat(titles(other.body)).contains("Hai guadagnato 222 punti").doesNotContain("Hai guadagnato 111 punti");
        assertThat(get("/v1/portal/inbox/unread-count", TOKENS.member(SUB_B)).body.path("memberId").asString()).isEqualTo(B);

        Reply popup = get("/v1/portal/popups/next", token);
        assertThat(popup.status).as("A è iscritto da meno di 7 giorni: il pop-up di benvenuto è in pubblico: " + popup.text)
                .isEqualTo(200);

        // proprietà generica: nessuna risposta di A contiene l'id di B, né l'e-mail o lo username del token
        for (Reply r : List.of(inbox, unread, popup, get("/v1/portal/content?placement=HOME_GRID", token),
                get("/v1/portal/theme", token))) {
            assertThat(r.status).as(r.text).isEqualTo(200);
            assertThat(r.text).doesNotContain(B).doesNotContain(OidcTestTokens.emailOf(SUB_A))
                    .doesNotContain(OidcTestTokens.usernameOf(SUB_A));
        }
    }

    @Test
    @DisplayName("[TB-ENG-MBP-021] A segna letto un proprio messaggio e tutti i propri: 200; l'attore è member:<id>, mai username o e-mail")
    void memberWritesItsOwnReads() {
        String token = TOKENS.member(SUB_A);
        awaitCommitted(MemberFactsSupport.pointsEarned(A, "EV-OIDC-A-READ-" + idBase(), 55)); // un non letto sicuro, in ogni ordine di esecuzione
        Reply inbox = get("/v1/portal/inbox?size=50", token);
        String unreadId = firstUnreadId(inbox.body);

        Reply read = request(HttpMethod.POST, "/v1/portal/inbox/" + unreadId + "/read", token, null, null, null);
        assertThat(read.status).as(read.text).isEqualTo(200);
        assertThat(read.body.path("read").asBoolean()).isTrue();
        assertThat(rowRead(unreadId)).isTrue();

        long bUnreadBefore = unreadOfMember(B);
        Reply all = request(HttpMethod.POST, "/v1/portal/inbox/read-all", token, null, null, null);
        assertThat(all.status).as(all.text).isEqualTo(200);
        assertThat(all.body.path("memberId").asString()).isEqualTo(A);
        assertThat(all.body.path("unread").asLong()).isZero();
        assertThat(unreadOfMember(B)).as("l'inbox di B non cambia").isEqualTo(bUnreadBefore);

        // un corpo vuoto o con memberId null è lo stesso di nessun corpo
        assertThat(request(HttpMethod.POST, "/v1/portal/inbox/read-all", token, null, MediaType.APPLICATION_JSON,
                "{\"memberId\":null}").status).isEqualTo(200);

        assertThat(ACTORS).anyMatch(a -> a.equals("POST /v1/portal/inbox/" + unreadId + "/read -> holder=member:" + A + " mdc=member:" + A));
        assertThat(ACTORS).anyMatch(a -> a.equals("POST /v1/portal/inbox/read-all -> holder=member:" + A + " mdc=member:" + A));
        // il token misto è un operatore (CARE:<username>): vale solo per le azioni compiute come membro
        assertThat(ACTORS).noneMatch(a -> a.contains("holder=member:")
                && (a.contains(OidcTestTokens.usernameOf(SUB_A)) || a.contains(OidcTestTokens.emailOf(SUB_A))));
    }

    @Test
    @DisplayName("[TB-ENG-MBP-022] un messaggio di B con il token di A: 404, e lo stato di B non cambia")
    void anotherMembersMessageIsNotFound() {
        awaitCommitted(MemberFactsSupport.pointsEarned(B, "EV-OIDC-B-OWN-" + idBase(), 444)); // un messaggio di B che solo questo caso legge
        String messageOfB = messageIdByTitle(B, "Hai guadagnato 444 punti");
        assertThat(rowRead(messageOfB)).isFalse();
        long bUnread = unreadOfMember(B);

        Reply r = request(HttpMethod.POST, "/v1/portal/inbox/" + messageOfB + "/read", TOKENS.member(SUB_A), null, null, null);
        assertThat(r.status).as(r.text).isEqualTo(404);
        assertThat(r.body.path("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(r.text).doesNotContain(B);
        assertThat(rowRead(messageOfB)).as("il messaggio di B resta non letto").isFalse();
        assertThat(unreadOfMember(B)).isEqualTo(bUnread);

        // un id che non esiste dà lo stesso 404: non si rivela se il messaggio è di un altro membro
        Reply missing = request(HttpMethod.POST, "/v1/portal/inbox/MSG-NON-ESISTE/read", TOKENS.member(SUB_A), null, null, null);
        assertThat(missing.status).isEqualTo(404);

        // B lo legge con il proprio token
        assertThat(request(HttpMethod.POST, "/v1/portal/inbox/" + messageOfB + "/read", TOKENS.member(SUB_B), null, null, null).status)
                .isEqualTo(200);
    }

    @Test
    @DisplayName("[TB-ENG-MBP-023] memberId in query in qualunque grafia, anche il proprio, su ogni funzione del portale: 400 MEMBER_FROM_TOKEN")
    void memberIdInTheQueryIsRefused() {
        String token = TOKENS.member(SUB_A);
        List<String> paths = new ArrayList<>();
        for (String query : List.of("?memberId=" + B, "?memberId=" + A, "?MEMBERID=" + B, "?member_id=" + B, "?!memberId=" + B,
                "?filter.memberId=" + B)) {
            paths.add("/v1/portal/inbox" + query);
            paths.add("/v1/portal/inbox/unread-count" + query);
        }
        for (String id : List.of(B, A)) {
            paths.add("/v1/portal/content?placement=HOME_GRID&memberId=" + id);
            paths.add("/v1/portal/popups/next?memberId=" + id);
            paths.add("/v1/portal/theme?memberId=" + id);
        }
        for (String path : paths) {
            Reply r = get(path, token);
            assertThat(r.status).as(path + " " + r.text).isEqualTo(400);
            assertThat(r.body.path("code").asString()).as(path).isEqualTo("MEMBER_FROM_TOKEN");
            assertThat(r.text).as(path).doesNotContain(B);
        }
        // anche le scritture con ?memberId: rifiutate prima del controller, nessun messaggio letto
        long bUnread = unreadOfMember(B);
        String messageOfB = anyMessageId(B);
        for (String path : List.of("/v1/portal/inbox/read-all?memberId=" + B, "/v1/portal/inbox/read-all?memberId=" + A,
                "/v1/portal/inbox/" + messageOfB + "/read?memberId=" + B, "/v1/portal/popups/POP-WELCOME/seen?memberId=" + B)) {
            Reply r = request(HttpMethod.POST, path, token, null, null, null);
            assertThat(r.status).as(path + " " + r.text).isEqualTo(400);
            assertThat(r.body.path("code").asString()).as(path).isEqualTo("MEMBER_FROM_TOKEN");
        }
        assertThat(unreadOfMember(B)).isEqualTo(bUnread);
        assertThat(rowRead(messageOfB)).isFalse();
    }

    @Test
    @DisplayName("[TB-ENG-MBP-024] memberId come campo form su una scrittura del portale: 400 MEMBER_FROM_TOKEN, prima ancora del corpo")
    void memberIdAsFormFieldIsRefused() {
        String token = TOKENS.member(SUB_A);
        long bUnread = unreadOfMember(B);
        for (String path : List.of("/v1/portal/inbox/read-all", "/v1/portal/popups/POP-WELCOME/seen")) {
            for (String form : List.of("memberId=" + B, "memberId=" + A, "MEMBERID=" + B)) {
                Reply r = request(HttpMethod.POST, path, token, null, MediaType.APPLICATION_FORM_URLENCODED, form);
                assertThat(r.status).as(path + " " + form + " " + r.text).isEqualTo(400);
                assertThat(r.body.path("code").asString()).isEqualTo("MEMBER_FROM_TOKEN");
                assertThat(r.text).doesNotContain(B);
            }
        }
        assertThat(unreadOfMember(B)).isEqualTo(bUnread);
        assertThat(popupViews(A)).isZero();
        assertThat(popupViews(B)).isZero();
    }

    @Test
    @DisplayName("[TB-ENG-MBP-025] memberId nel corpo (anche il proprio): 400 MEMBER_FROM_TOKEN, nessuna riga scritta")
    void memberIdInTheBodyIsRefused() {
        String token = TOKENS.member(SUB_A);
        String messageOfA = anyMessageId(A);
        long aUnread = unreadOfMember(A);
        long bUnread = unreadOfMember(B);
        long popupsA = popupViews(A);
        long popupsB = popupViews(B);

        for (String id : List.of(B, A)) {
            String body = "{\"memberId\":\"" + id + "\"}";
            for (String path : List.of("/v1/portal/inbox/read-all", "/v1/portal/inbox/" + messageOfA + "/read")) {
                Reply r = request(HttpMethod.POST, path, token, null, MediaType.APPLICATION_JSON, body);
                assertThat(r.status).as(path + " " + body + " " + r.text).isEqualTo(400);
                assertThat(r.body.path("code").asString()).isEqualTo("MEMBER_FROM_TOKEN");
                assertThat(r.text).doesNotContain(B);
            }
            Reply seen = request(HttpMethod.POST, "/v1/portal/popups/POP-WELCOME/seen", token, null, MediaType.APPLICATION_JSON,
                    "{\"memberId\":\"" + id + "\",\"dismissed\":true}");
            assertThat(seen.status).as(seen.text).isEqualTo(400);
            assertThat(seen.body.path("code").asString()).isEqualTo("MEMBER_FROM_TOKEN");
        }
        assertThat(unreadOfMember(A)).isEqualTo(aUnread);
        assertThat(unreadOfMember(B)).isEqualTo(bUnread);
        assertThat(popupViews(A)).isEqualTo(popupsA);
        assertThat(popupViews(B)).isEqualTo(popupsB);
    }

    @Test
    @DisplayName("[TB-ENG-MBP-026] X-LH-Member in oidc: 400 MEMBER_FROM_TOKEN, anche con il proprio id, in lettura e in scrittura")
    void demoMemberHeaderIsRefused() {
        for (String id : List.of(B, A)) {
            for (String path : List.of("/v1/portal/inbox", "/v1/portal/inbox/unread-count", "/v1/portal/popups/next",
                    "/v1/portal/content?placement=HOME_GRID", "/v1/portal/theme")) {
                Reply r = request(HttpMethod.GET, path, TOKENS.member(SUB_A), id, null, null);
                assertThat(r.status).as(path + " " + r.text).isEqualTo(400);
                assertThat(r.body.path("code").asString()).isEqualTo("MEMBER_FROM_TOKEN");
            }
            Reply write = request(HttpMethod.POST, "/v1/portal/inbox/read-all", TOKENS.member(SUB_A), id, null, null);
            assertThat(write.status).as(write.text).isEqualTo(400);
            assertThat(write.body.path("code").asString()).isEqualTo("MEMBER_FROM_TOKEN");
        }
    }

    @Test
    @DisplayName("[TB-ENG-MBP-027] la vista di un pop-up è del solo titolare: la scrittura di D non tocca le viste di A e B")
    void popupSeenBelongsToTheHolder() {
        String token = TOKENS.member(SUB_D);
        long popupsA = popupViews(A);
        long popupsB = popupViews(B);
        Reply seen = request(HttpMethod.POST, "/v1/portal/popups/POP-WELCOME/seen", token, null, MediaType.APPLICATION_JSON,
                "{\"dismissed\":true}");
        assertThat(seen.status).as(seen.text).isEqualTo(204);
        assertThat(popupViews(D)).isEqualTo(1);
        assertThat(popupViews(A)).as("nessuna vista di A").isEqualTo(popupsA);
        assertThat(popupViews(B)).as("nessuna vista di B").isEqualTo(popupsB);
        assertThat(ACTORS).anyMatch(a -> a.equals("POST /v1/portal/popups/POP-WELCOME/seen -> holder=member:" + D + " mdc=member:" + D));
        // il token misto è un operatore (CARE:<username>): vale solo per le azioni compiute come membro
        assertThat(ACTORS).noneMatch(a -> a.contains("holder=member:")
                && (a.contains(OidcTestTokens.usernameOf(SUB_D)) || a.contains(OidcTestTokens.emailOf(SUB_D))));

        // senza corpo vale lo stesso (vista non chiusa, stesso giorno: idempotente); un contenuto che non è un pop-up non esiste come tale
        assertThat(request(HttpMethod.POST, "/v1/portal/popups/POP-WELCOME/seen", token, null, null, null).status).isEqualTo(204);
        assertThat(popupViews(D)).isEqualTo(1);
        assertThat(request(HttpMethod.POST, "/v1/portal/popups/CNT-FRIEND/seen", token, null, null, null).status).isEqualTo(404);
        assertThat(popupViews(A)).isEqualTo(popupsA);
        assertThat(popupViews(B)).isEqualTo(popupsB);
    }

    // ---------- operatori, token misti, fonti ----------

    @Test
    @DisplayName("[TB-ENG-MBP-028] un operatore CARE non agisce come membro: 403 MEMBER_REQUIRED su inbox, non letti, pop-up e scritture")
    void operatorIsNotAMember() {
        String token = TOKENS.operator("carla", "CARE");
        for (String path : List.of("/v1/portal/inbox", "/v1/portal/inbox/unread-count", "/v1/portal/popups/next")) {
            Reply r = get(path, token);
            assertThat(r.status).as(path + " " + r.text).isEqualTo(403);
            assertThat(r.body.path("code").asString()).isEqualTo("MEMBER_REQUIRED");
        }
        for (String path : List.of("/v1/portal/inbox/read-all", "/v1/portal/inbox/" + anyMessageId(A) + "/read",
                "/v1/portal/popups/POP-WELCOME/seen")) {
            Reply r = request(HttpMethod.POST, path, token, null, null, null);
            assertThat(r.status).as(path + " " + r.text).isEqualTo(403);
            assertThat(r.body.path("code").asString()).isEqualTo("MEMBER_REQUIRED");
        }
    }

    @Test
    @DisplayName("[TB-ENG-MBP-029] contenuti (OPTIONAL): un operatore CARE ha la vista generica, il membro la propria; il tema è per tutti")
    void contentIsGenericForAnOperator() {
        // A entra nel segmento SEG-DIGITAL: CNT-DIGITAL-THANKS (HOME_GRID) è per lui e per nessun altro
        awaitCommitted(MemberFactsSupport.segmentEntered(A, "SEG-DIGITAL"));
        String grid = "/v1/portal/content?placement=HOME_GRID";
        Reply generic = get(grid, TOKENS.operator("carla", "CARE"));
        assertThat(generic.status).as(generic.text).isEqualTo(200);
        assertThat(generic.body.isArray()).isTrue();
        assertThat(generic.body.size()).isPositive();
        assertThat(generic.text).doesNotContain(A).doesNotContain(B);
        Reply own = get(grid, TOKENS.member(SUB_A));
        assertThat(own.status).as(own.text).isEqualTo(200);
        // la vista del membro è personalizzata, quella dell'operatore no: né per il token semplice né per il misto
        assertThat(contentCodes(own.body)).contains("CNT-DIGITAL-THANKS");
        assertThat(contentCodes(generic.body)).doesNotContain("CNT-DIGITAL-THANKS").contains("CNT-FRIEND");
        assertThat(contentCodes(get(grid, TOKENS.mixed(SUB_A, "CARE")).body)).isEqualTo(contentCodes(generic.body));
        // B non è in quel segmento: vede la propria vista, senza quel contenuto
        assertThat(contentCodes(get(grid, TOKENS.member(SUB_B)).body)).doesNotContain("CNT-DIGITAL-THANKS");
        assertThat(get("/v1/portal/theme", TOKENS.operator("carla", "CARE")).status).isEqualTo(200);
        assertThat(get("/v1/portal/theme", TOKENS.member(SUB_B)).status).isEqualTo(200);
        // un placement sbagliato resta un 400 di dominio, per il membro come per l'operatore
        assertThat(get("/v1/portal/content?placement=NON-ESISTE", TOKENS.member(SUB_A)).status).isEqualTo(400);
    }

    @Test
    @DisplayName("[TB-ENG-MBP-030] token misto MEMBER+CARE: vale come operatore, 403 sulle funzioni del membro, vista generica sui contenuti")
    void mixedTokenIsNotAMember() {
        String token = TOKENS.mixed(SUB_A, "CARE");
        Reply r = get("/v1/portal/inbox", token);
        assertThat(r.status).as(r.text).isEqualTo(403);
        assertThat(r.body.path("code").asString()).isEqualTo("MEMBER_REQUIRED");
        assertThat(r.text).doesNotContain(A);
        assertThat(request(HttpMethod.POST, "/v1/portal/inbox/read-all", token, null, null, null).status).isEqualTo(403);
        Reply content = get("/v1/portal/content?placement=HOME_GRID", token);
        assertThat(content.status).as(content.text).isEqualTo(200);
        assertThat(contentCodes(content.body)).doesNotContain("CNT-DIGITAL-THANKS").contains("CNT-FRIEND");
    }

    @Test
    @DisplayName("[TB-ENG-MBP-031] MEMBER+SOURCE: 403 sul portale, mai membro")
    void memberPlusSourceIsForbidden() {
        String token = TOKENS.memberSource(SUB_A, "src-ecommerce");
        for (String path : List.of("/v1/portal/inbox", "/v1/portal/inbox/unread-count", "/v1/portal/popups/next",
                "/v1/portal/content?placement=HOME_GRID", "/v1/portal/theme")) {
            Reply r = get(path, token);
            assertThat(r.status).as(path + " " + r.text).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("[TB-ENG-MBP-032] un token di membro sulle API di backoffice (R5 senza members): 403 FORBIDDEN_ROLE")
    void memberTokenCannotReachBackofficeReads() {
        String token = TOKENS.member(SUB_A);
        for (String path : List.of("/v1/messages?memberId=" + B, "/v1/messages", "/v1/contents", "/v1/contents/preview?memberId=" + B + "&placement=HOME_GRID",
                "/v1/message-templates", "/v1/notification-rules", "/v1/theme", "/v1/webhooks")) {
            Reply r = get(path, token);
            assertThat(r.status).as(path + " " + r.text).isEqualTo(403);
            assertThat(r.body.path("code").asString()).as(path).isEqualTo("FORBIDDEN_ROLE");
            assertThat(r.text).as(path).doesNotContain("Hai guadagnato");
        }
    }

    @Test
    @DisplayName("[TB-ENG-MBP-033] X-LH-Actor è ignorato con un token: il membro resta membro")
    void actorHeaderIsIgnored() {
        Reply r = request(HttpMethod.GET, "/v1/messages?memberId=" + B, TOKENS.member(SUB_A), null, null, null, "ADMIN:intruso");
        assertThat(r.status).as(r.text).isEqualTo(403);
        assertThat(r.body.path("code").asString()).isEqualTo("FORBIDDEN_ROLE");
    }

    // ---------- legame assente, anonimizzato, token non validi ----------

    @Test
    @DisplayName("[TB-ENG-MBP-034] un sub non ancora legato: 409 MEMBER_NOT_LINKED con Retry-After, contenuti generici, poi 200 appena arriva il fatto")
    void unlinkedSubjectGets409UntilTheFactArrives() {
        String token = TOKENS.member(SUB_NEW);
        for (String path : List.of("/v1/portal/inbox", "/v1/portal/inbox/unread-count", "/v1/portal/popups/next")) {
            Reply r = get(path, token);
            assertThat(r.status).as(path + " " + r.text).isEqualTo(409);
            assertThat(r.body.path("code").asString()).isEqualTo("MEMBER_NOT_LINKED");
            assertThat(r.retryAfter).isEqualTo("2");
        }
        assertThat(request(HttpMethod.POST, "/v1/portal/inbox/read-all", token, null, null, null).status).isEqualTo(409);
        // contenuti (OPTIONAL) e tema non richiedono il membro: la vista generica c'è subito
        Reply unlinkedView = get("/v1/portal/content?placement=HOME_GRID", token);
        assertThat(unlinkedView.status).as(unlinkedView.text).isEqualTo(200);
        assertThat(contentCodes(unlinkedView.body)).isEqualTo(contentCodes(get("/v1/portal/content?placement=HOME_GRID",
                TOKENS.operator("carla", "CARE")).body)).doesNotContain("CNT-DIGITAL-THANKS").contains("CNT-FRIEND");
        assertThat(get("/v1/portal/theme", token).status).isEqualTo(200);

        String member = String.format("MBR-%06d", idBase() + 4);
        awaitCommitted(MemberFactsSupport.registered(member, ref(SUB_NEW), "2026-09-02T10:00:00Z", 2));
        Reply linked = get("/v1/portal/inbox/unread-count", token);
        assertThat(linked.status).as(linked.text).isEqualTo(200);
        assertThat(linked.body.path("memberId").asString()).isEqualTo(member);
    }

    @Test
    @DisplayName("[TB-ENG-MBP-035] C anonimizzato: 409 e il replay del fatto di registrazione non ri-lega")
    void anonymizedMemberIsUnlinkedForGood() {
        String token = TOKENS.member(SUB_C);
        assertThat(get("/v1/portal/inbox", token).status).isEqualTo(200);

        awaitCommitted(MemberFactsSupport.statusChanged(C, "ANONYMIZED", "2026-09-03T10:00:00Z"));
        Reply gone = get("/v1/portal/inbox", token);
        assertThat(gone.status).as(gone.text).isEqualTo(409);
        assertThat(gone.body.path("code").asString()).isEqualTo("MEMBER_NOT_LINKED");
        assertThat(gone.retryAfter).isEqualTo("2");

        // replay del fatto di registrazione e un member.updated più recente: la lapide vince
        awaitCommitted(MemberFactsSupport.registered(C, ref(SUB_C), "2026-09-01T10:00:00Z", 2),
                MemberFactsSupport.updated(C, ref(SUB_C), "2026-09-05T10:00:00Z", 2));
        assertThat(get("/v1/portal/inbox", token).status).isEqualTo(409);
        assertThat(get("/v1/portal/inbox/unread-count", token).status).isEqualTo(409);
        assertThat(get("/v1/portal/popups/next", token).status).isEqualTo(409);
    }

    @Test
    @DisplayName("[TB-ENG-MBP-036] token assente, scaduto, firmato con un'altra chiave, audience o emittente sbagliati: 401")
    void invalidTokensAreUnauthorized() {
        for (String path : List.of("/v1/portal/inbox", "/v1/portal/content?placement=HOME_GRID", "/v1/portal/theme")) {
            assertThat(get(path, null).status).as(path).isEqualTo(401);
            assertThat(get(path, TOKENS.expired(SUB_A)).status).as(path).isEqualTo(401);
            assertThat(get(path, TOKENS.foreignKey(SUB_A)).status).as(path).isEqualTo(401);
            assertThat(get(path, TOKENS.wrongAudience(SUB_A)).status).as(path).isEqualTo(401);
            assertThat(get(path, TOKENS.wrongIssuer(SUB_A)).status).as(path).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("[TB-ENG-MBP-037] un messaggio letto da A: non compare nell'inbox di B; i due non letti restano separati")
    void unreadCountsAreSeparate() {
        // B ha almeno un messaggio non letto e A non lo vede nemmeno dopo aver letto tutto
        String extra = "EV-OIDC-EXTRA-" + idBase() + "-" + UUID.randomUUID();
        awaitCommitted(MemberFactsSupport.pointsEarned(B, extra, 333));
        assertThat(request(HttpMethod.POST, "/v1/portal/inbox/read-all", TOKENS.member(SUB_A), null, null, null).status).isEqualTo(200);
        Reply a = get("/v1/portal/inbox/unread-count", TOKENS.member(SUB_A));
        Reply b = get("/v1/portal/inbox/unread-count", TOKENS.member(SUB_B));
        assertThat(a.body.path("unread").asLong()).isZero();
        assertThat(b.body.path("unread").asLong()).isPositive();
        assertThat(titles(get("/v1/portal/inbox?size=50", TOKENS.member(SUB_B)).body)).contains("Hai guadagnato 333 punti");
        assertThat(titles(get("/v1/portal/inbox?size=50", TOKENS.member(SUB_A)).body)).doesNotContain("Hai guadagnato 333 punti");
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

    private static List<String> titles(JsonNode page) {
        List<String> out = new ArrayList<>();
        for (JsonNode m : page.path("items")) {
            out.add(m.path("title").asString());
        }
        return out;
    }

    private static List<String> contentCodes(JsonNode view) {
        List<String> out = new ArrayList<>();
        for (JsonNode c : view) {
            out.add(c.path("code").asString());
        }
        return out;
    }

    private static long unreadOf(JsonNode page) {
        long n = 0;
        for (JsonNode m : page.path("items")) {
            if (!m.path("read").asBoolean()) {
                n++;
            }
        }
        return n;
    }

    private static String firstUnreadId(JsonNode page) {
        for (JsonNode m : page.path("items")) {
            if (!m.path("read").asBoolean()) {
                return m.path("id").asString();
            }
        }
        throw new AssertionError("nessun messaggio non letto: " + page);
    }

    private String anyMessageId(String memberId) {
        return jdbc.sql("SELECT id FROM inbox_message WHERE member_id = ? AND channel = 'INAPP' ORDER BY id LIMIT 1")
                .param(memberId).query(String.class).single();
    }

    private String messageIdByTitle(String memberId, String title) {
        return jdbc.sql("SELECT id FROM inbox_message WHERE member_id = ? AND title = ? LIMIT 1")
                .params(memberId, title).query(String.class).single();
    }

    private boolean rowRead(String messageId) {
        return jdbc.sql("SELECT read_at IS NOT NULL FROM inbox_message WHERE id = ?").param(messageId).query(Boolean.class).single();
    }

    private long unreadOfMember(String memberId) {
        return jdbc.sql("SELECT count(*) FROM inbox_message WHERE member_id = ? AND channel = 'INAPP' AND read_at IS NULL")
                .param(memberId).query(Long.class).single();
    }

    private long popupViews(String memberId) {
        return jdbc.sql("SELECT count(*) FROM popup_view WHERE member_id = ?").param(memberId).query(Long.class).single();
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
