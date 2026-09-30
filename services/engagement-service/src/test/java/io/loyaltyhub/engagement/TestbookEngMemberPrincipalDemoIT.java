package io.loyaltyhub.engagement;

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
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Il profilo {@code demo} del portale di engagement è invariato (CLAUDE.md regola 6-bis, ADR-048, Q-555; righe
 * TB-ENG-MBP-040…049 del testbook, docs/testbook/TB-ENG-engagement.md §18): il membro è il {@code memberId} in query o
 * nel corpo (deprecato) oppure l'header {@code X-LH-Member} messo dal BFF; gli errori restano quelli di prima. Membri del
 * seed (Marco {@code MBR-000002}, Giulia {@code MBR-000003}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestbookEngMemberPrincipalDemoIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String MEMBER = "MBR-000002";
    private static final String OTHER = "MBR-000003";
    private static final String WRITER = "MBR-000007"; // Chiara: l'unico membro le cui letture le cambia il caso 043
    private static final String PERSONA = "ANALYST:anonymous"; // l'attore che il BFF demo manda per una persona membro

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=engagement");
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
    @DisplayName("[TB-ENG-MBP-040] demo: X-LH-Member risolve il membro su inbox, non letti e pop-up (stesso JSON del memberId in query)")
    void headerResolvesTheMember() {
        Reply header = call(HttpMethod.GET, "/v1/portal/inbox", PERSONA, MEMBER, null, null);
        Reply query = call(HttpMethod.GET, "/v1/portal/inbox?memberId=" + MEMBER, PERSONA, null, null, null);
        assertThat(header.status).as(header.text).isEqualTo(200);
        assertThat(header.body.path("page").path("totalItems").asLong()).isEqualTo(6); // Marco: 6 messaggi seminati
        assertThat(header.body).isEqualTo(query.body);

        Reply unread = call(HttpMethod.GET, "/v1/portal/inbox/unread-count", PERSONA, MEMBER, null, null);
        assertThat(unread.body.path("memberId").asString()).isEqualTo(MEMBER);
        assertThat(unread.body.path("unread").asLong()).isEqualTo(3);
        assertThat(call(HttpMethod.GET, "/v1/portal/inbox/unread-count?memberId=" + MEMBER, PERSONA, null, null, null).body)
                .isEqualTo(unread.body);

        Reply popupHeader = call(HttpMethod.GET, "/v1/portal/popups/next", PERSONA, MEMBER, null, null);
        Reply popupQuery = call(HttpMethod.GET, "/v1/portal/popups/next?memberId=" + MEMBER, PERSONA, null, null, null);
        assertThat(popupHeader.status).isEqualTo(popupQuery.status);
        assertThat(popupHeader.text).isEqualTo(popupQuery.text);
    }

    @Test
    @DisplayName("[TB-ENG-MBP-041] demo: i contenuti del portale con X-LH-Member coincidono con quelli del memberId in query")
    void contentMatchesTheQueryForm() {
        Reply header = call(HttpMethod.GET, "/v1/portal/content?placement=HOME_GRID", PERSONA, MEMBER, null, null);
        Reply query = call(HttpMethod.GET, "/v1/portal/content?placement=HOME_GRID&memberId=" + MEMBER, PERSONA, null, null, null);
        assertThat(header.status).as(header.text).isEqualTo(200);
        assertThat(header.body.size()).isPositive();
        assertThat(header.body).isEqualTo(query.body);
        assertThat(call(HttpMethod.GET, "/v1/portal/theme", PERSONA, null, null, null).status).isEqualTo(200);
    }

    @Test
    @DisplayName("[TB-ENG-MBP-042] demo: il memberId nel corpo (deprecato) è ancora una fonte valida di read-all e seen")
    void bodyMemberIsStillAccepted() {
        Reply all = call(HttpMethod.POST, "/v1/portal/inbox/read-all", PERSONA, null, MediaType.APPLICATION_JSON,
                "{\"memberId\":\"" + OTHER + "\"}");
        assertThat(all.status).as(all.text).isEqualTo(200);
        assertThat(all.body.path("memberId").asString()).isEqualTo(OTHER);
        Reply seen = call(HttpMethod.POST, "/v1/portal/popups/POP-WEEKEND/seen", PERSONA, null, MediaType.APPLICATION_JSON,
                "{\"memberId\":\"" + OTHER + "\",\"dismissed\":true}");
        assertThat(seen.status).as(seen.text).isEqualTo(204);
    }

    @Test
    @DisplayName("[TB-ENG-MBP-043] demo: scritture con X-LH-Member senza memberId nel corpo (il web migrato): read e read-all del solo titolare")
    void writesWithTheHeaderOnly() {
        Reply inbox = call(HttpMethod.GET, "/v1/portal/inbox?size=50", PERSONA, WRITER, null, null);
        String unreadId = null;
        for (JsonNode m : inbox.body.path("items")) {
            if (!m.path("read").asBoolean()) {
                unreadId = m.path("id").asString();
                break;
            }
        }
        assertThat(unreadId).isNotNull();
        Reply read = call(HttpMethod.POST, "/v1/portal/inbox/" + unreadId + "/read", PERSONA, WRITER, null, null);
        assertThat(read.status).as(read.text).isEqualTo(200);
        assertThat(read.body.path("read").asBoolean()).isTrue();
        Reply all = call(HttpMethod.POST, "/v1/portal/inbox/read-all", PERSONA, WRITER, null, null);
        assertThat(all.body.path("memberId").asString()).isEqualTo(WRITER);
        assertThat(all.body.path("unread").asLong()).isZero();
    }

    @Test
    @DisplayName("[TB-ENG-MBP-044] demo: due fonti diverse (header e query, o header e corpo) ⇒ 400 MEMBER_MISMATCH, senza ripetere gli id")
    void differentSourcesAreAMismatch() {
        Reply query = call(HttpMethod.GET, "/v1/portal/inbox?memberId=" + MEMBER, PERSONA, OTHER, null, null);
        assertThat(query.status).as(query.text).isEqualTo(400);
        assertThat(query.body.path("code").asString()).isEqualTo("MEMBER_MISMATCH");
        assertThat(query.body.path("detail").asString()).doesNotContain(MEMBER).doesNotContain(OTHER);

        Reply body = call(HttpMethod.POST, "/v1/portal/inbox/read-all", PERSONA, OTHER, MediaType.APPLICATION_JSON,
                "{\"memberId\":\"" + MEMBER + "\"}");
        assertThat(body.status).as(body.text).isEqualTo(400);
        assertThat(body.body.path("code").asString()).isEqualTo("MEMBER_MISMATCH");
        // uguali: nessun disaccordo
        assertThat(call(HttpMethod.POST, "/v1/portal/inbox/read-all", PERSONA, OTHER, MediaType.APPLICATION_JSON,
                "{\"memberId\":\"" + OTHER + "\"}").status).isEqualTo(200);
    }

    @Test
    @DisplayName("[TB-ENG-MBP-045] demo: senza membro gli errori restano quelli di prima (400 con lo stesso testo)")
    void missingMemberKeepsTheOldErrors() {
        assertThat(call(HttpMethod.GET, "/v1/portal/inbox", PERSONA, null, null, null).body.path("detail").asString())
                .isEqualTo("Parametro memberId obbligatorio.");
        assertThat(call(HttpMethod.GET, "/v1/portal/inbox/unread-count", PERSONA, null, null, null).body.path("detail").asString())
                .isEqualTo("Parametro memberId obbligatorio.");
        assertThat(call(HttpMethod.POST, "/v1/portal/inbox/read-all", PERSONA, null, null, null).status).isEqualTo(400);
        assertThat(call(HttpMethod.GET, "/v1/portal/popups/next", PERSONA, null, null, null).body.path("detail").asString())
                .isEqualTo("memberId è obbligatorio");
        assertThat(call(HttpMethod.GET, "/v1/portal/content?placement=HOME_GRID", PERSONA, null, null, null).body.path("detail").asString())
                .isEqualTo("memberId è obbligatorio");
        assertThat(call(HttpMethod.POST, "/v1/portal/popups/POP-WELCOME/seen", PERSONA, null, null, null).body.path("detail").asString())
                .isEqualTo("memberId è obbligatorio");
    }

    @Test
    @DisplayName("[TB-ENG-MBP-046] demo: X-LH-Member di forma non valida ⇒ 400")
    void malformedHeaderIsABadRequest() {
        Reply r = call(HttpMethod.GET, "/v1/portal/inbox", PERSONA, "non-un-membro", null, null);
        assertThat(r.status).as(r.text).isEqualTo(400);
    }

    @Test
    @DisplayName("[TB-ENG-MBP-047] demo: l'utenza SOURCE non usa il portale ⇒ 403 (tema compreso), come per le letture R5")
    void sourceIsForbidden() {
        for (String path : new String[]{"/v1/portal/inbox", "/v1/portal/inbox/unread-count", "/v1/portal/popups/next",
                "/v1/portal/content?placement=HOME_GRID", "/v1/portal/theme"}) {
            assertThat(call(HttpMethod.GET, path, "SOURCE:src-ecommerce", MEMBER, null, null).status).as(path).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("[TB-ENG-MBP-048] demo: un messaggio di un altro membro ⇒ 404 come prima; il messaggio di un altro non cambia")
    void anotherMembersMessageIsNotFound() {
        Reply other = call(HttpMethod.GET, "/v1/portal/inbox?size=1", PERSONA, OTHER, null, null);
        String otherId = other.body.path("items").get(0).path("id").asString();
        Reply r = call(HttpMethod.POST, "/v1/portal/inbox/" + otherId + "/read", PERSONA, MEMBER, null, null);
        assertThat(r.status).as(r.text).isEqualTo(404);
        Reply again = call(HttpMethod.GET, "/v1/portal/inbox?size=1", PERSONA, OTHER, null, null);
        assertThat(again.body).isEqualTo(other.body);
    }

    @Test
    @DisplayName("[TB-ENG-MBP-049] demo: nessun ruolo nell'header e il membro esplicito: l'attore ANALYST:anonymous e il memberId in query restano validi per smoke e hub")
    void explicitMemberWithoutAnyHeaderStillWorks() {
        // Lo stesso comportamento di prima della migrazione: il BFF non c'è, il chiamante indica il memberId
        Reply r = call(HttpMethod.GET, "/v1/portal/inbox/unread-count?memberId=" + MEMBER, "ADMIN:demo", null, null, null);
        assertThat(r.status).as(r.text).isEqualTo(200);
        assertThat(r.body.path("memberId").asString()).isEqualTo(MEMBER);
    }

    @Test
    @DisplayName("[TB-ENG-MBP-050] demo: memberId in query vuoto e memberId nel corpo ⇒ 400 MEMBER_MISMATCH (non più il ripiego sul corpo)")
    void blankQueryWithABodyMemberIsAMismatch() {
        Reply r = call(HttpMethod.POST, "/v1/portal/inbox/read-all?memberId=", PERSONA, null, MediaType.APPLICATION_JSON,
                "{\"memberId\":\"" + MEMBER + "\"}");
        assertThat(r.status).as(r.text).isEqualTo(400);
        assertThat(r.body.path("code").asString()).isEqualTo("MEMBER_MISMATCH");
        assertThat(r.body.path("detail").asString()).doesNotContain(MEMBER);
    }

    // ---------- supporto ----------

    private record Reply(int status, String text, JsonNode body) {
    }

    private Reply call(HttpMethod method, String path, String actor, String memberHeader, MediaType contentType, String body) {
        RestClient.RequestBodySpec spec = RestClient.builder().baseUrl("http://localhost:" + port).build()
                .method(method).uri(path).header("X-LH-Actor", actor);
        if (memberHeader != null) {
            spec.header("X-LH-Member", memberHeader);
        }
        if (contentType != null) {
            spec.contentType(contentType);
        }
        if (body != null) {
            spec.body(body);
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
