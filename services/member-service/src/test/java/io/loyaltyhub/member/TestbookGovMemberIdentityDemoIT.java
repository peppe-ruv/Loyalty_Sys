package io.loyaltyhub.member;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import io.loyaltyhub.testsupport.TopicReader;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-GOV §8.3 — il membro nel profilo {@code demo} (MID, regola 6-bis): nessun token, il membro è il {@code memberId}
 * esplicito o {@code X-LH-Member}; i percorsi legacy con l'id funzionano come prima; la registrazione dal portale
 * senza token crea un membro senza legame; gli errori sono quelli di oggi. Oracolo: docs/06 §3.4, docs/15 (Q-553,
 * Q-555), ADR-048.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "loyaltyhub.member.segments.reannounce-delay-ms=0")
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestbookGovMemberIdentityDemoIT {

    private static final EmbeddedPostgres PG = startPg();

    private final ObjectMapper mapper = new ObjectMapper();
    private int seq;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcClient jdbc;

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

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gov/member-identity-demo.csv", numLinesToSkip = 1)
    void identity(String id, String description, String scenario, String expected) {
        assertThat(run(scenario)).isEqualTo(expected);
    }

    private String run(String scenario) {
        switch (scenario) {
            case "REG": {
                Reply r = call("POST", "/v1/portal/members", null, registration());
                String memberId = r.body.path("memberId").asString();
                long links = jdbc.sql("SELECT count(*) FROM member_identity WHERE member_id = ?").param(memberId)
                        .query(Long.class).single();
                String channel = jdbc.sql("SELECT channel FROM member WHERE id = ?").param(memberId).query(String.class)
                        .single();
                return outcome(r) + ":" + (links == 0 ? "nolink" : "LINK") + ":" + channel;
            }
            case "PROFILE_PARAM":
                return withMember(call("GET", "/v1/portal/me/profile?memberId=MBR-000002", null, null));
            case "PROFILE_HEADER":
                return withMember(call("GET", "/v1/portal/me/profile", null, null, "X-LH-Member", "MBR-000003"));
            case "PROFILE_BOTH_SAME":
                return withMember(call("GET", "/v1/portal/me/profile?memberId=MBR-000002", null, null,
                        "X-LH-Member", "MBR-000002"));
            case "MISMATCH":
                return outcome(call("GET", "/v1/portal/me/profile?memberId=MBR-000003", null, null,
                        "X-LH-Member", "MBR-000002"));
            case "MISSING":
                return outcome(call("GET", "/v1/portal/me/profile", null, null));
            case "BAD_HEADER":
                return outcome(call("GET", "/v1/portal/me/profile", null, null, "X-LH-Member", "MBR-12"));
            case "REFERRAL_HEADER":
                return outcome(call("GET", "/v1/portal/me/referral", null, null, "X-LH-Member", "MBR-000002"));
            case "LEGACY_PROFILE":
                return withMember(call("GET", "/v1/portal/members/MBR-000002", null, null));
            case "LEGACY_REFERRAL":
                return outcome(call("GET", "/v1/portal/members/MBR-000002/referral", null, null));
            case "LEGACY_MISMATCH":
                return outcome(call("GET", "/v1/portal/members/MBR-000002", null, null, "X-LH-Member", "MBR-000003"));
            case "LEGACY_UNKNOWN":
                return outcome(call("GET", "/v1/portal/members/MBR-999999", null, null));
            case "SOURCE":
                return outcome(call("GET", "/v1/portal/me/profile?memberId=MBR-000002", "SOURCE:src-ecommerce", null));
            case "PATCH_AUDIT": {
                Reply created = call("POST", "/v1/portal/members", null, registration());
                String memberId = created.body.path("memberId").asString();
                Reply r = call("PATCH", "/v1/portal/me/profile", null, Map.of("city", "Lodi"), "X-LH-Member", memberId);
                List<JsonNode> audit = new TopicReader(jdbc, mapper, "lh.audit.v1")
                        .published(List.of(), "MEMBER:" + memberId, "io.loyaltyhub.audit.entry").stream()
                        .filter(e -> e.path("data").path("action").asString().equals("UPDATE")).toList();
                return outcome(r) + ":" + audit.size() + ":" + (audit.isEmpty() ? "-" : audit.get(0).path("lhactor").asString()
                        .replace(memberId, "<id>"));
            }
            case "REG_AUDIT": {
                Reply created = call("POST", "/v1/portal/members", null, registration());
                String memberId = created.body.path("memberId").asString();
                List<JsonNode> audit = new TopicReader(jdbc, mapper, "lh.audit.v1")
                        .published(List.of(), "MEMBER:" + memberId, "io.loyaltyhub.audit.entry");
                return outcome(created) + ":" + audit.get(0).path("lhactor").asString().replace(memberId, "<id>");
            }
            default:
                throw new IllegalArgumentException("Scenario sconosciuto: " + scenario);
        }
    }

    private Map<String, Object> registration() {
        int n = ++seq;
        return Map.of("firstName", "Demo", "lastName", "Portale" + n, "email", "demo.portale" + n + "@profili.test");
    }

    private static String outcome(Reply r) {
        String code = r.status >= 400 ? r.body.path("code").asString() : "";
        return r.status + (code.isBlank() ? "" : ":" + code);
    }

    private static String withMember(Reply r) {
        return outcome(r) + ":" + r.body.path("memberId").asString();
    }

    private record Reply(int status, JsonNode body) {
    }

    private Reply call(String method, String path, String actor, Object body, String... headers) {
        var spec = RestClient.create("http://localhost:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (actor != null) {
            spec = spec.header("X-LH-Actor", actor);
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            spec = spec.header(headers[i], headers[i + 1]);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes());
            return new Reply(res.getStatusCode().value(), text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text));
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
