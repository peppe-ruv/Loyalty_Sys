package io.loyaltyhub.gamification;

import io.loyaltyhub.testsupport.ListenerGroups;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Il profilo {@code demo} del portale del gioco è invariato (CLAUDE.md regola 6-bis, ADR-048, Q-555; righe
 * TB-GAM-MBP-040…048 del testbook, docs/testbook/TB-GAM-gioco.md §22): il membro è il {@code memberId} esplicito (query o
 * corpo) o l'header {@code X-LH-Member} messo dal BFF; gli errori restano quelli di prima e {@code resolve=ids} vale ancora
 * per il BFF. Membri del seed; la giocata consuma solo il contesto di questa classe.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestbookGamMemberPrincipalDemoIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String MEMBER = "MBR-000010"; // 2 crediti + la gratuita di oggi su IW-AUTUNNO (3 giocate)
    private static final String OTHER = "MBR-000003";
    private static final String PERSONA = "ANALYST:anonymous"; // l'attore che il BFF demo manda per una persona membro
    private static final String CONTEST = "IW-AUTUNNO";
    private static final String BOARD = "LDB-MONTH-PTS";
    private static final List<String> READS = List.of("/v1/portal/achievements", "/v1/portal/badges", "/v1/portal/contests",
            "/v1/portal/contests/" + CONTEST + "/plays", "/v1/portal/leaderboards", "/v1/portal/leaderboards/" + BOARD);

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private JdbcClient jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=gamification");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @BeforeAll
    void waitForListenerGroup() {
        ListenerGroups.awaitStable(listeners);
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    @DisplayName("[TB-GAM-MBP-040] demo: X-LH-Member risolve il membro su tutte le letture del portale")
    void headerResolvesTheMember() {
        for (String path : READS) {
            Reply r = call(HttpMethod.GET, path, PERSONA, MEMBER, null);
            assertThat(r.status).as(path + " " + r.text).isEqualTo(200);
        }
        Reply contests = call(HttpMethod.GET, "/v1/portal/contests", PERSONA, MEMBER, null);
        assertThat(contests.body.get(0).path("code").asString()).isEqualTo(CONTEST);
        assertThat(contests.body.get(0).has("playsAvailable")).isTrue();
    }

    @Test
    @DisplayName("[TB-GAM-MBP-041] demo: il memberId in query resta una fonte valida e risponde lo stesso JSON dell'header")
    void explicitQueryMemberIsStillAccepted() {
        for (String path : READS) {
            Reply query = call(HttpMethod.GET, path + (path.contains("?") ? "&" : "?") + "memberId=" + MEMBER, PERSONA, null, null);
            Reply header = call(HttpMethod.GET, path, PERSONA, MEMBER, null);
            assertThat(query.status).as(path + " " + query.text).isEqualTo(200);
            assertThat(query.body).as(path).isEqualTo(header.body);
        }
        // entrambe le fonti concordi: nessun conflitto
        assertThat(call(HttpMethod.GET, "/v1/portal/badges?memberId=" + MEMBER, PERSONA, MEMBER, null).status).isEqualTo(200);
    }

    @Test
    @DisplayName("[TB-GAM-MBP-042] demo: due fonti diverse (header e query) ⇒ 400 MEMBER_MISMATCH, gli id non sono ripetuti")
    void differentSourcesAreAMismatch() {
        for (String path : READS) {
            Reply r = call(HttpMethod.GET, path + (path.contains("?") ? "&" : "?") + "memberId=" + MEMBER, PERSONA, OTHER, null);
            assertThat(r.status).as(path + " " + r.text).isEqualTo(400);
            assertThat(r.body.path("code").asString()).isEqualTo("MEMBER_MISMATCH");
            assertThat(r.body.path("detail").asString()).doesNotContain(MEMBER).doesNotContain(OTHER);
        }
    }

    @Test
    @DisplayName("[TB-GAM-MBP-043] demo: senza membro le letture danno 400 «Parametro obbligatorio assente: memberId» (come prima)")
    void missingMemberIsABadRequest() {
        for (String path : READS) {
            Reply r = call(HttpMethod.GET, path, PERSONA, null, null);
            assertThat(r.status).as(path + " " + r.text).isEqualTo(400);
            assertThat(r.body.path("detail").asString()).contains("Parametro obbligatorio assente: memberId");
        }
    }

    @Test
    @DisplayName("[TB-GAM-MBP-044] demo: X-LH-Member di forma non valida ⇒ 400; l'utenza SOURCE non usa il portale ⇒ 403")
    void malformedHeaderAndSource() {
        assertThat(call(HttpMethod.GET, "/v1/portal/achievements", PERSONA, "non-un-membro", null).status).isEqualTo(400);
        for (String path : READS) {
            assertThat(call(HttpMethod.GET, path, "SOURCE:src-ecommerce", MEMBER, null).status).as(path).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("[TB-GAM-MBP-045] demo: la giocata accetta header, memberId nel corpo o entrambi concordi; attore member:<id>")
    void playAcceptsEveryDemoSource() {
        String path = "/v1/portal/contests/" + CONTEST + "/play";
        // corpo legacy {memberId}: come prima (PlayIT)
        Reply body = call(HttpMethod.POST, path, PERSONA, null, "{\"memberId\":\"" + MEMBER + "\"}");
        assertThat(body.status).as(body.text).isEqualTo(200);
        // header e corpo in disaccordo: 400 MEMBER_MISMATCH, nessuna giocata in più
        long plays = count();
        Reply mismatch = call(HttpMethod.POST, path, PERSONA, OTHER, "{\"memberId\":\"" + MEMBER + "\"}");
        assertThat(mismatch.status).as(mismatch.text).isEqualTo(400);
        assertThat(mismatch.body.path("code").asString()).isEqualTo("MEMBER_MISMATCH");
        assertThat(count()).isEqualTo(plays);
        // solo l'header, corpo {}: la giocata è del membro dell'header
        Reply header = call(HttpMethod.POST, path, PERSONA, MEMBER, "{}");
        assertThat(header.status).as(header.text).isEqualTo(200);
        assertThat(jdbc.sql("SELECT member_id FROM play WHERE id = ?").param(header.body.path("playId").asString())
                .query(String.class).single()).isEqualTo(MEMBER);
        // l'attore del fatto è member:<id> (Q-556), non più MEMBER:<id>
        List<String> actors = jdbc.sql("SELECT DISTINCT payload ->> 'lhactor' FROM outbox WHERE type = 'io.loyaltyhub.fact.contest.played'"
                + " AND payload ->> 'subject' = ?").param("member:" + MEMBER).query(String.class).list();
        assertThat(actors).containsExactly("member:" + MEMBER);
    }

    @Test
    @DisplayName("[TB-GAM-MBP-046] demo: giocata senza membro ⇒ 422 MEMBER_REQUIRED, concorso sconosciuto ⇒ 404 (come prima)")
    void playWithoutMemberIsUnchanged() {
        Reply none = call(HttpMethod.POST, "/v1/portal/contests/" + CONTEST + "/play", PERSONA, null, "{}");
        assertThat(none.status).as(none.text).isEqualTo(422);
        assertThat(none.body.path("code").asString()).isEqualTo("MEMBER_REQUIRED");
        assertThat(call(HttpMethod.POST, "/v1/portal/contests/IW-NOPE/play", PERSONA, MEMBER, "{}").status).isEqualTo(404);
    }

    @Test
    @DisplayName("[TB-GAM-MBP-047] demo: resolve=ids restituisce ancora i memberId (variante del BFF); un altro valore è 400")
    void resolveIdsStillWorksInDemo() {
        Reply ids = call(HttpMethod.GET, "/v1/portal/leaderboards/" + BOARD + "?resolve=ids", PERSONA, MEMBER, null);
        assertThat(ids.status).as(ids.text).isEqualTo(200);
        for (JsonNode e : ids.body.path("top")) {
            assertThat(e.path("memberId").asString()).startsWith("MBR-");
            assertThat(e.hasNonNull("nickname")).isFalse();
        }
        Reply plain = call(HttpMethod.GET, "/v1/portal/leaderboards/" + BOARD, PERSONA, MEMBER, null);
        assertThat(plain.text).doesNotContain("MBR-");
        assertThat(call(HttpMethod.GET, "/v1/portal/leaderboards/" + BOARD + "?resolve=nicknames", PERSONA, MEMBER, null).status)
                .isEqualTo(400);
    }

    @Test
    @DisplayName("[TB-GAM-MBP-048] demo: il backoffice non cambia: X-LH-Actor con ruolo, lettura di concorsi e classifiche")
    void backofficeIsUnchanged() {
        assertThat(call(HttpMethod.GET, "/v1/contests", "MARKETING:luca", null, null).status).isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/v1/leaderboards/" + BOARD + "/ranking", "ANALYST:sara", null, null).status).isEqualTo(200);
    }

    // ---------- supporto ----------

    private record Reply(int status, String text, JsonNode body) {
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM play").query(Long.class).single();
    }

    private Reply call(HttpMethod method, String path, String actor, String memberHeader, String json) {
        RestClient.RequestBodySpec spec = RestClient.builder().baseUrl("http://localhost:" + port).build()
                .method(method).uri(path).header("X-LH-Actor", actor);
        if (memberHeader != null) {
            spec.header("X-LH-Member", memberHeader);
        }
        if (json != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(json);
        }
        return spec.exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes());
            return new Reply(res.getStatusCode().value(), text, text.isBlank() ? null : mapper.readTree(text));
        });
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
