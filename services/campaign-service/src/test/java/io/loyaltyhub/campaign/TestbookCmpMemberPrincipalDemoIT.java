package io.loyaltyhub.campaign;

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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Il profilo {@code demo} dell'elenco «Guadagna» è invariato (CLAUDE.md regola 6-bis, ADR-048, Q-555, Q-560; righe
 * TB-CMP-MBP-040…047 del testbook, docs/testbook/TB-CMP-campagne.md §16): il membro è il {@code memberId} esplicito o
 * l'header {@code X-LH-Member} messo dal BFF; senza membro (BO-17, Q-560) la vista generica; gli errori restano quelli di
 * prima. Membri del seed (sola lettura): {@code MBR-000004} è GOLD, {@code MBR-000002} SILVER.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestbookCmpMemberPrincipalDemoIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String GOLD_MEMBER = "MBR-000004";
    private static final String SILVER_MEMBER = "MBR-000002";
    private static final String GOLD = "CMP-GOLD-PURCHASE-PLAY";
    private static final String EVERYONE = "CMP-PURCHASE-BASE";
    private static final String PATH = "/v1/portal/campaigns";
    private static final String PERSONA = "ANALYST:anonymous"; // l'attore che il BFF demo manda per una persona membro

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=campaign");
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
    @DisplayName("[TB-CMP-MBP-040] demo: X-LH-Member risolve il membro: il GOLD vede la campagna GOLD, il SILVER no")
    void headerResolvesTheMember() {
        Reply gold = get(PATH, PERSONA, GOLD_MEMBER);
        Reply silver = get(PATH, PERSONA, SILVER_MEMBER);
        assertThat(gold.status).as(gold.text).isEqualTo(200);
        assertThat(codes(gold)).contains(EVERYONE, GOLD);
        assertThat(codes(silver)).contains(EVERYONE).doesNotContain(GOLD);
    }

    @Test
    @DisplayName("[TB-CMP-MBP-041] demo: il memberId esplicito in query è una fonte valida e risponde lo stesso JSON dell'header")
    void explicitQueryMemberIsStillAccepted() {
        Reply viaQuery = get(PATH + "?memberId=" + GOLD_MEMBER, PERSONA, null);
        Reply viaHeader = get(PATH, PERSONA, GOLD_MEMBER);
        assertThat(viaQuery.status).as(viaQuery.text).isEqualTo(200);
        assertThat(viaQuery.body).isEqualTo(viaHeader.body);
        // stessa fonte due volte: coerente
        assertThat(get(PATH + "?memberId=" + GOLD_MEMBER, PERSONA, GOLD_MEMBER).status).isEqualTo(200);
    }

    @Test
    @DisplayName("[TB-CMP-MBP-042] demo: senza membro (BO-17, Q-560) la vista generica, senza errori: le regole senza pubblico, non quella GOLD")
    void noMemberIsTheGenericView() {
        Reply r = get(PATH, PERSONA, null);
        assertThat(r.status).as(r.text).isEqualTo(200);
        assertThat(codes(r)).contains(EVERYONE).doesNotContain(GOLD);
        Reply referral = get(PATH + "?codes=CMP-REFERRAL-REFERRER,CMP-REFERRAL-REFEREE", PERSONA, null);
        assertThat(codes(referral)).containsExactlyInAnyOrder("CMP-REFERRAL-REFERRER", "CMP-REFERRAL-REFEREE");
        assertThat(referral.body.get(0).path("memberLimit").isObject() || referral.body.get(1).path("memberLimit").isObject()).isTrue();
    }

    @Test
    @DisplayName("[TB-CMP-MBP-043] demo: due fonti diverse (header e query) ⇒ 400 MEMBER_MISMATCH, gli id non sono ripetuti")
    void differentSourcesAreAMismatch() {
        Reply r = get(PATH + "?memberId=" + SILVER_MEMBER, PERSONA, GOLD_MEMBER);
        assertThat(r.status).as(r.text).isEqualTo(400);
        assertThat(r.body.path("code").asString()).isEqualTo("MEMBER_MISMATCH");
        assertThat(r.body.path("detail").asString()).doesNotContain(SILVER_MEMBER).doesNotContain(GOLD_MEMBER);
    }

    @Test
    @DisplayName("[TB-CMP-MBP-044] demo: X-LH-Member di forma non valida ⇒ 400")
    void malformedHeaderIsABadRequest() {
        assertThat(get(PATH, PERSONA, "non-un-membro").status).isEqualTo(400);
    }

    @Test
    @DisplayName("[TB-CMP-MBP-045] demo: l'utenza SOURCE non usa il portale ⇒ 403, come per le letture R5")
    void sourceIsForbidden() {
        assertThat(get(PATH, "SOURCE:src-ecommerce", GOLD_MEMBER).status).isEqualTo(403);
        assertThat(get(PATH + "?memberId=" + GOLD_MEMBER, "SOURCE:src-ecommerce", null).status).isEqualTo(403);
    }

    @Test
    @DisplayName("[TB-CMP-MBP-046] demo: un membro sconosciuto o un memberId libero danno la vista generica (200), come prima")
    void unknownMemberIsTheGenericView() {
        for (String id : List.of("MBR-999999", "non-un-id-di-membro")) {
            Reply r = get(PATH + "?memberId=" + id, PERSONA, null);
            assertThat(r.status).as(id + " " + r.text).isEqualTo(200);
            assertThat(codes(r)).as(id).contains(EVERYONE).doesNotContain(GOLD);
        }
    }

    @Test
    @DisplayName("[TB-CMP-MBP-047] demo: l'attore del backoffice (BO-17) legge per codice senza intestazione del membro, con ogni ruolo di lettura")
    void backofficeReadsByCodesWithoutAMember() {
        for (String actor : List.of("ADMIN:anna", "MARKETING:marco", "LEGAL:lea", "CARE:carla", "ANALYST:anonymous")) {
            Reply r = get(PATH + "?codes=CMP-REFERRAL-REFERRER,CMP-REFERRAL-REFEREE", actor, null);
            assertThat(r.status).as(actor + " " + r.text).isEqualTo(200);
            assertThat(codes(r)).as(actor).containsExactlyInAnyOrder("CMP-REFERRAL-REFERRER", "CMP-REFERRAL-REFEREE");
        }
    }

    // ---------- supporto ----------

    private record Reply(int status, String text, JsonNode body) {
    }

    private static List<String> codes(Reply reply) {
        List<String> out = new ArrayList<>();
        if (reply.body != null && reply.body.isArray()) {
            reply.body.forEach(v -> out.add(v.path("code").asString()));
        }
        return out;
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
