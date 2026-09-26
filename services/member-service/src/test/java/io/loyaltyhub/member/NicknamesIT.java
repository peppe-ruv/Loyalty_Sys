package io.loyaltyhub.member;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Soprannomi a lotti per il BFF (Q-368, ADR-032, F2-EVT-02; docs/servizi/member-service.md §3): {@code POST
 * /v1/members/nicknames} risponde una voce per id richiesto (senza doppioni, nell'ordine), {@code null} per un id
 * sconosciuto, il segnaposto per un membro anonimizzato; da 1 a 200 id, altrimenti {@code 400} (RFC 9457).
 * Contesto separato da {@link MemberServiceIT} (che conta i membri del seed). Senza Docker: EmbeddedKafka + Zonky.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NicknamesIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String ADMIN = "ADMIN:marta.admin";
    private static final String PATH = "/v1/members/nicknames";

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=member");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    void returnsOneItemPerRequestedIdInOrder() {
        JsonNode res = send(Map.of("memberIds", List.of("MBR-000005", "MBR-999999", "MBR-000001", "MBR-000005", " ")), 200);
        JsonNode items = res.path("items");
        assertThat(items.size()).as("senza doppioni né vuoti").isEqualTo(3);
        assertThat(items.get(0).path("memberId").asString()).isEqualTo("MBR-000005");
        assertThat(items.get(0).path("nickname").asString()).isEqualTo("fra_r");
        assertThat(items.get(1).path("memberId").asString()).isEqualTo("MBR-999999");
        assertThat(items.get(1).path("nickname").isNull()).as("membro sconosciuto: nickname null").isTrue();
        assertThat(items.get(2).path("nickname").asString()).isEqualTo("anna_r");
        assertThat(res.toString()).as("solo id e soprannome, nessun altro dato personale")
                .doesNotContain("firstName").doesNotContain("email").doesNotContain("@");
    }

    @Test
    void anonymizedMembersGetThePlaceholder() {
        JsonNode seeded = send(Map.of("memberIds", List.of("MBR-000012")), 200).path("items").get(0);
        assertThat(seeded.path("nickname").asString()).isEqualTo("Membro anonimo");

        String id = exchange("POST", "/v1/members", "CARE:paolo.care", Map.of(
                "firstName", "Ermenegilda", "lastName", "Quartucci", "nickname", "ermy_q",
                "email", "ermenegilda.quartucci@example.org", "channel", "WEB"), 201).path("id").asString();
        assertThat(send(Map.of("memberIds", List.of(id)), 200).path("items").get(0).path("nickname").asString())
                .isEqualTo("ermy_q");
        exchange("POST", "/v1/members/" + id + "/anonymize", ADMIN, Map.of("confirm", id), 200);
        JsonNode after = send(Map.of("memberIds", List.of(id)), 200);
        assertThat(after.path("items").get(0).path("nickname").asString()).isEqualTo("Membro anonimo");
        assertThat(after.toString()).doesNotContain("ermy_q").doesNotContain("Ermenegilda");
    }

    @Test
    void acceptsUpTo200Ids() {
        assertThat(send(Map.of("memberIds", ids(200)), 200).path("items").size()).isEqualTo(200);
    }

    @Test
    void rejectsMoreThan200IdsWithTooManyIds() {
        JsonNode problem = send(Map.of("memberIds", ids(201)), 400);
        assertThat(problem.path("code").asString()).isEqualTo("TOO_MANY_IDS");
        assertThat(problem.path("type").asString()).isEqualTo("urn:loyaltyhub:problem:bad-request");
        assertThat(problem.path("status").asInt()).isEqualTo(400);
        assertThat(problem.path("errors").get(0).path("field").asString()).isEqualTo("memberIds");
    }

    @Test
    void rejectsEmptyOrMissingIds() {
        JsonNode empty = send(Map.of("memberIds", List.of()), 400);
        assertThat(empty.path("code").asString()).isEqualTo("BAD_REQUEST");
        assertThat(empty.path("errors").get(0).path("field").asString()).isEqualTo("memberIds");
        assertThat(send(Map.of(), 400).path("code").asString()).isEqualTo("BAD_REQUEST");
        assertThat(send(Map.of("memberIds", List.of("", "  ")), 400).path("code").asString()).isEqualTo("BAD_REQUEST");
    }

    // ---------- helper ----------

    private static List<String> ids(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add("MBR-%06d".formatted(i));
        }
        return out;
    }

    private JsonNode send(Object body, int expected) {
        return exchange("POST", PATH, null, body, expected);
    }

    private JsonNode exchange(String method, String path, String actor, Object body, int expected) {
        var spec = RestClient.create("http://localhost:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (actor != null) {
            spec = spec.header("X-LH-Actor", actor);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(res.getStatusCode().value()).as(method + " " + path + " → " + text).isEqualTo(expected);
            return text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text);
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
