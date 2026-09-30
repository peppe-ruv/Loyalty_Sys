package io.loyaltyhub.wallet;

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
 * Il profilo {@code demo} del wallet del portale è invariato (CLAUDE.md regola 6-bis, ADR-048, Q-555; righe
 * TB-WAL-MBP-040…048 del testbook, docs/testbook/TB-WAL-wallet.md §22): il membro è il {@code memberId} del percorso
 * legacy o l'header {@code X-LH-Member} messo dal BFF; i percorsi legacy e i nuovi {@code /v1/portal/me/wallet} rispondono
 * lo stesso JSON, gli errori restano quelli di prima. Membri del seed (sola lettura).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestbookWalMemberPrincipalDemoIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String MEMBER = "MBR-000002";
    private static final String PERSONA = "ANALYST:anonymous"; // l'attore che il BFF demo manda per una persona membro

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=wallet");
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
    @DisplayName("[TB-WAL-MBP-040] demo: X-LH-Member risolve il membro su /v1/portal/me/wallet")
    void headerResolvesTheMember() {
        Reply r = get("/v1/portal/me/wallet", PERSONA, MEMBER);
        assertThat(r.status).as(r.text).isEqualTo(200);
        assertThat(r.body.path("memberId").asString()).isEqualTo(MEMBER);
        assertThat(r.body.path("tier").path("code").asString()).isEqualTo("SILVER");
    }

    @Test
    @DisplayName("[TB-WAL-MBP-041] demo: il percorso legacy /v1/portal/wallets/{id} risponde lo stesso JSON di /me/wallet")
    void legacyPathMatchesTheNewOne() {
        Reply legacy = get("/v1/portal/wallets/" + MEMBER, PERSONA, null);
        Reply me = get("/v1/portal/me/wallet", PERSONA, MEMBER);
        assertThat(legacy.status).as(legacy.text).isEqualTo(200);
        assertThat(me.body).isEqualTo(legacy.body);
        // il legacy con l'header dello stesso membro è coerente
        assertThat(get("/v1/portal/wallets/" + MEMBER, PERSONA, MEMBER).status).isEqualTo(200);
    }

    @Test
    @DisplayName("[TB-WAL-MBP-042] demo: le attività di /me/wallet/activity coincidono con quelle del percorso legacy")
    void activityMatchesTheLegacyPath() {
        Reply legacy = get("/v1/portal/wallets/" + MEMBER + "/activity?size=5", PERSONA, null);
        Reply me = get("/v1/portal/me/wallet/activity?size=5", PERSONA, MEMBER);
        assertThat(legacy.status).as(legacy.text).isEqualTo(200);
        assertThat(me.body).isEqualTo(legacy.body);
    }

    @Test
    @DisplayName("[TB-WAL-MBP-043] demo: il memberId esplicito in query è una fonte valida (smoke, hub, testbook)")
    void explicitQueryMemberIsStillAccepted() {
        Reply r = get("/v1/portal/me/wallet?memberId=" + MEMBER, PERSONA, null);
        assertThat(r.status).as(r.text).isEqualTo(200);
        assertThat(r.body.path("memberId").asString()).isEqualTo(MEMBER);
    }

    @Test
    @DisplayName("[TB-WAL-MBP-044] demo: due fonti diverse (header e percorso) ⇒ 400 MEMBER_MISMATCH")
    void differentSourcesAreAMismatch() {
        Reply r = get("/v1/portal/wallets/" + MEMBER, PERSONA, "MBR-000003");
        assertThat(r.status).as(r.text).isEqualTo(400);
        assertThat(r.body.path("code").asString()).isEqualTo("MEMBER_MISMATCH");
        assertThat(r.body.path("detail").asString()).doesNotContain(MEMBER).doesNotContain("MBR-000003");
    }

    @Test
    @DisplayName("[TB-WAL-MBP-045] demo: senza membro /me/wallet dà 400 «Parametro obbligatorio assente: memberId»")
    void missingMemberIsABadRequest() {
        Reply r = get("/v1/portal/me/wallet", PERSONA, null);
        assertThat(r.status).as(r.text).isEqualTo(400);
        assertThat(r.body.path("detail").asString()).contains("Parametro obbligatorio assente: memberId");
    }

    @Test
    @DisplayName("[TB-WAL-MBP-046] demo: X-LH-Member di forma non valida ⇒ 400")
    void malformedHeaderIsABadRequest() {
        Reply r = get("/v1/portal/me/wallet", PERSONA, "non-un-membro");
        assertThat(r.status).as(r.text).isEqualTo(400);
    }

    @Test
    @DisplayName("[TB-WAL-MBP-047] demo: l'utenza SOURCE non usa il portale ⇒ 403, come per le letture R5")
    void sourceIsForbidden() {
        assertThat(get("/v1/portal/me/wallet", "SOURCE:src-ecommerce", MEMBER).status).isEqualTo(403);
        assertThat(get("/v1/portal/wallets/" + MEMBER, "SOURCE:src-ecommerce", null).status).isEqualTo(403);
    }

    @Test
    @DisplayName("[TB-WAL-MBP-048] demo: /v1/portal/editions è la lista di /v1/editions; un membro sconosciuto dà 404 come prima")
    void editionsAliasAndUnknownMember() {
        Reply portal = get("/v1/portal/editions", PERSONA, null);
        Reply backoffice = get("/v1/editions", PERSONA, null);
        assertThat(portal.status).as(portal.text).isEqualTo(200);
        assertThat(portal.body).isEqualTo(backoffice.body);
        assertThat(get("/v1/portal/wallets/MBR-999999", PERSONA, null).status).isEqualTo(404);
        assertThat(get("/v1/portal/tiers", PERSONA, null).status).isEqualTo(200);
    }

    // ---------- supporto ----------

    private record Reply(int status, String text, JsonNode body) {
    }

    private Reply get(String path, String actor, String memberHeader) {
        RestClient.RequestHeadersSpec<?> spec = RestClient.builder().baseUrl("http://localhost:" + port).build()
                .get().uri(path).header("X-LH-Actor", actor);
        if (memberHeader != null) {
            spec = spec.header("X-LH-Member", memberHeader);
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
