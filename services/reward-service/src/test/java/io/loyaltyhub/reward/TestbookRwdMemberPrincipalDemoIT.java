package io.loyaltyhub.reward;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Il profilo {@code demo} del portale di reward-service è invariato (CLAUDE.md regola 6-bis, ADR-048, Q-555; righe
 * TB-RWD-MBP-040…048 del testbook, docs/testbook/TB-RWD-premi.md §21): il membro è il {@code memberId} esplicito o
 * l'header {@code X-LH-Member} messo dal BFF, gli errori restano quelli di prima e i dati del seed sono gli stessi. Membri
 * del seed; le scritture (richieste premio) sono di un membro che nessun'altra classe usa.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestbookRwdMemberPrincipalDemoIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String PERSONA = "ANALYST:anonymous"; // l'attore che il BFF demo manda per una persona membro
    private static final String READER = "MBR-000004"; // coupon e richieste nel seed
    private static final String OTHER = "MBR-000003";
    private static final String REWARD = "RWD-DONATION-TREE"; // INSTANT, senza spedizione né limiti

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
        registry.add("spring.datasource.url", () -> base + "&currentSchema=reward");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
        registry.add("loyaltyhub.reward.redemption-timeout.enabled", () -> "false");
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
    @DisplayName("[TB-RWD-MBP-040] demo: X-LH-Member risolve il membro su catalogo, coupon e richieste, come il memberId esplicito")
    void headerResolvesTheMember() {
        for (String path : new String[] {"/v1/portal/coupons", "/v1/portal/redemptions", "/v1/portal/catalog"}) {
            Reply byHeader = get(path, PERSONA, READER);
            Reply byQuery = get(path + "?memberId=" + READER, PERSONA, null);
            assertThat(byHeader.status).as(path + " " + byHeader.text).isEqualTo(200);
            assertThat(byHeader.body).as(path).isEqualTo(byQuery.body);
        }
        Reply coupons = get("/v1/portal/coupons", PERSONA, READER);
        assertThat(coupons.body.isEmpty()).isFalse();
        Reply redemptions = get("/v1/portal/redemptions", PERSONA, READER);
        assertThat(redemptions.body.isEmpty()).isFalse();
        for (JsonNode r : redemptions.body) {
            assertThat(r.path("memberId").asString()).isEqualTo(READER);
        }
    }

    @Test
    @DisplayName("[TB-RWD-MBP-041] demo: il catalogo dipende dal membro (livello) e senza membro è la vista generica")
    void catalogIsPersonalisedOnlyWithAMember() {
        Reply generic = get("/v1/portal/catalog", PERSONA, null);
        Reply blank = get("/v1/portal/catalog?memberId=", PERSONA, null);
        Reply platinum = get("/v1/portal/catalog", PERSONA, "MBR-000005");
        Reply base = get("/v1/portal/catalog", PERSONA, "MBR-000001");
        assertThat(generic.status).as(generic.text).isEqualTo(200);
        assertThat(blank.body).isEqualTo(generic.body);
        // un premio riservato al livello PLATINUM è bloccato per il generico e per il membro base, aperto per chi ha il livello
        assertThat(lockedByTier(generic.body, "RWD-PLATINUM-EVENT")).isTrue();
        assertThat(lockedByTier(base.body, "RWD-PLATINUM-EVENT")).isTrue();
        assertThat(lockedByTier(platinum.body, "RWD-PLATINUM-EVENT")).isFalse();
        assertThat(get("/v1/portal/rewards/RWD-DONATION-TREE", PERSONA, READER).status).isEqualTo(200);
        assertThat(get("/v1/portal/rewards/RWD-DONATION-TREE", PERSONA, null).status).isEqualTo(200);
        assertThat(get("/v1/portal/rewards/RWD-NON-ESISTE", PERSONA, READER).status).isEqualTo(404);
    }

    @Test
    @DisplayName("[TB-RWD-MBP-042] demo: due fonti diverse (header e query) ⇒ 400 MEMBER_MISMATCH, gli id non sono ripetuti")
    void differentSourcesAreAMismatch() {
        Reply r = get("/v1/portal/coupons?memberId=" + OTHER, PERSONA, READER);
        assertThat(r.status).as(r.text).isEqualTo(400);
        assertThat(r.body.path("code").asString()).isEqualTo("MEMBER_MISMATCH");
        assertThat(r.body.path("detail").asString()).doesNotContain(READER).doesNotContain(OTHER);
    }

    @Test
    @DisplayName("[TB-RWD-MBP-043] demo: senza membro (o con memberId vuoto) i 400 sono quelli di prima")
    void missingMemberKeepsTheOldErrors() {
        for (String path : new String[] {"/v1/portal/coupons", "/v1/portal/coupons?memberId=", "/v1/portal/redemptions",
                "/v1/portal/redemptions?memberId="}) {
            Reply r = get(path, PERSONA, null);
            assertThat(r.status).as(path + " " + r.text).isEqualTo(400);
            assertThat(r.body.path("detail").asString()).as(path).isEqualTo("memberId è obbligatorio");
        }
        Reply cancel = send(HttpMethod.POST, "/v1/portal/redemptions/RDM-000003/cancel", PERSONA, null, null, null);
        assertThat(cancel.status).as(cancel.text).isEqualTo(400);
        assertThat(cancel.body.path("detail").asString()).isEqualTo("memberId è obbligatorio");
        Reply post = send(HttpMethod.POST, "/v1/portal/redemptions", PERSONA, null, MediaType.APPLICATION_JSON,
                "{\"rewardCode\":\"" + REWARD + "\"}");
        assertThat(post.status).as(post.text).isEqualTo(400);
        assertThat(post.body.path("detail").asString()).isEqualTo("memberId e rewardCode sono obbligatori");
    }

    @Test
    @DisplayName("[TB-RWD-MBP-044] demo: la richiesta premio accetta il memberId del corpo (deprecato) o l'header, e rifiuta un disaccordo")
    void redemptionSourcesInDemo() {
        // solo il corpo, come oggi
        Reply legacy = send(HttpMethod.POST, "/v1/portal/redemptions", PERSONA, null, MediaType.APPLICATION_JSON,
                "{\"memberId\":\"MBR-000009\",\"rewardCode\":\"" + REWARD + "\"}");
        assertThat(legacy.status).as(legacy.text).isEqualTo(202);
        assertThat(owner(legacy)).isEqualTo("MBR-000009");
        // solo l'header
        Reply byHeader = send(HttpMethod.POST, "/v1/portal/redemptions", PERSONA, "MBR-000009", MediaType.APPLICATION_JSON,
                "{\"rewardCode\":\"" + REWARD + "\"}");
        assertThat(byHeader.status).as(byHeader.text).isEqualTo(202);
        assertThat(owner(byHeader)).isEqualTo("MBR-000009");
        // header e corpo concordi
        assertThat(send(HttpMethod.POST, "/v1/portal/redemptions", PERSONA, "MBR-000009", MediaType.APPLICATION_JSON,
                "{\"memberId\":\"MBR-000009\",\"rewardCode\":\"" + REWARD + "\"}").status).isEqualTo(202);
        // header e corpo in disaccordo
        Reply mismatch = send(HttpMethod.POST, "/v1/portal/redemptions", PERSONA, "MBR-000009", MediaType.APPLICATION_JSON,
                "{\"memberId\":\"" + OTHER + "\",\"rewardCode\":\"" + REWARD + "\"}");
        assertThat(mismatch.status).as(mismatch.text).isEqualTo(400);
        assertThat(mismatch.body.path("code").asString()).isEqualTo("MEMBER_MISMATCH");
        assertThat(jdbc.sql("SELECT count(*) FROM redemption WHERE member_id = ?").param(OTHER).query(Long.class).single())
                .isEqualTo(2L); // solo quelle del seed (RDM-000009 e RDM-000019)
    }

    @Test
    @DisplayName("[TB-RWD-MBP-045] demo: una richiesta altrui dà 404 se il chiamante indica un membro, si legge senza; l'annullo altrui è 404")
    void ownershipInDemo() {
        assertThat(get("/v1/portal/redemptions/RDM-000003", PERSONA, null).status).isEqualTo(200);
        assertThat(get("/v1/portal/redemptions/RDM-000003?memberId=MBR-000011", PERSONA, null).status).isEqualTo(200);
        assertThat(get("/v1/portal/redemptions/RDM-000003", PERSONA, "MBR-000011").status).isEqualTo(200);
        Reply other = get("/v1/portal/redemptions/RDM-000003?memberId=" + OTHER, PERSONA, null);
        assertThat(other.status).as(other.text).isEqualTo(404);
        assertThat(other.body.path("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(get("/v1/portal/redemptions/RDM-000003", PERSONA, OTHER).status).isEqualTo(404);
        Reply cancel = send(HttpMethod.POST, "/v1/portal/redemptions/RDM-000003/cancel", PERSONA, OTHER, null, null);
        assertThat(cancel.status).as(cancel.text).isEqualTo(404);
        // una richiesta appena fatta si annulla con l'header del titolare
        Reply made = send(HttpMethod.POST, "/v1/portal/redemptions", PERSONA, "MBR-000010", MediaType.APPLICATION_JSON,
                "{\"rewardCode\":\"" + REWARD + "\"}");
        String id = made.body.path("redemptionId").asString();
        Reply own = send(HttpMethod.POST, "/v1/portal/redemptions/" + id + "/cancel", PERSONA, "MBR-000010", null, null);
        assertThat(own.status).as(own.text).isEqualTo(200);
        assertThat(own.body.path("status").asString()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("[TB-RWD-MBP-046] demo: X-LH-Member di forma non valida ⇒ 400; l'utenza SOURCE non usa il portale ⇒ 403")
    void malformedHeaderAndSource() {
        assertThat(get("/v1/portal/coupons", PERSONA, "non-un-membro").status).isEqualTo(400);
        assertThat(get("/v1/portal/catalog", PERSONA, "non-un-membro").status).isEqualTo(400);
        for (String path : new String[] {"/v1/portal/catalog", "/v1/portal/coupons", "/v1/portal/redemptions",
                "/v1/portal/reward-categories"}) {
            assertThat(get(path, "SOURCE:src-ecommerce", READER).status).as(path).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("[TB-RWD-MBP-047] demo: le scritture del membro portano l'attore member:<id> (Q-556)")
    void writesCarryTheMemberActor() {
        Reply made = send(HttpMethod.POST, "/v1/portal/redemptions", PERSONA, "MBR-000006", MediaType.APPLICATION_JSON,
                "{\"rewardCode\":\"" + REWARD + "\"}");
        assertThat(made.status).as(made.text).isEqualTo(202);
        String id = made.body.path("redemptionId").asString();
        assertThat(jdbc.sql("SELECT actor FROM redemption WHERE id = ?").param(id).query(String.class).single())
                .isEqualTo("member:MBR-000006");
        assertThat(jdbc.sql("SELECT payload->>'lhactor' FROM outbox WHERE topic = 'lh.facts.v1' AND payload->'data'->>'redemptionId' = ?")
                .param(id).query(String.class).single()).isEqualTo("member:MBR-000006");
        // il seed usa la stessa forma
        assertThat(jdbc.sql("SELECT count(*) FROM redemption_history WHERE actor LIKE 'MEMBER:%'").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("[TB-RWD-MBP-048] demo: /v1/portal/reward-categories è la lista di /v1/reward-categories")
    void categoriesAlias() {
        Reply portal = get("/v1/portal/reward-categories", PERSONA, null);
        Reply backoffice = get("/v1/reward-categories", PERSONA, null);
        assertThat(portal.status).as(portal.text).isEqualTo(200);
        assertThat(portal.body).isEqualTo(backoffice.body);
        assertThat(get("/v1/portal/reward-categories", PERSONA, READER).status).isEqualTo(200);
    }

    // ---------- supporto ----------

    private String owner(Reply made) {
        return jdbc.sql("SELECT member_id FROM redemption WHERE id = ?").param(made.body.path("redemptionId").asString())
                .query(String.class).single();
    }

    private static boolean lockedByTier(JsonNode catalog, String code) {
        for (JsonNode band : catalog.path("bands")) {
            for (JsonNode reward : band.path("rewards")) {
                if (code.equals(reward.path("code").asString())) {
                    return !reward.path("lockedByTier").isNull() && !reward.path("lockedByTier").isMissingNode();
                }
            }
        }
        throw new AssertionError("premio assente dal catalogo: " + code);
    }

    private record Reply(int status, String text, JsonNode body) {
    }

    private Reply get(String path, String actor, String memberHeader) {
        return send(HttpMethod.GET, path, actor, memberHeader, null, null);
    }

    private Reply send(HttpMethod method, String path, String actor, String memberHeader, MediaType contentType, String body) {
        RestClient.RequestBodySpec spec = RestClient.builder().baseUrl("http://localhost:" + port).build()
                .method(method).uri(path).header("X-LH-Actor", actor);
        if (memberHeader != null) {
            spec = spec.header("X-LH-Member", memberHeader);
        }
        if (contentType != null) {
            spec = spec.contentType(contentType);
        }
        if (body != null) {
            spec = spec.body(body);
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
