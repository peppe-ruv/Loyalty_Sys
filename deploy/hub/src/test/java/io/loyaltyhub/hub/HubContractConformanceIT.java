package io.loyaltyhub.hub;

import io.loyaltyhub.common.event.JsonSchemaValidator;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = HubApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.name=hub")
@ActiveProfiles({"demo", "inproc"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class HubContractConformanceIT {

    private static final EmbeddedPostgres PG = startPg();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ObjectMapper mapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=ingestion,member,campaign,wallet,insight,reward,gamification,engagement");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private JsonNode post(String uri, String actor, Object body) {
        var req = client().post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON);
        if (actor != null) req.header("X-LH-Actor", actor);
        if (body != null) req.body(body);
        return req.retrieve().body(JsonNode.class);
    }

    private JsonNode patch(String uri, String actor, Object body) {
        var req = client().method(HttpMethod.PATCH).uri(uri)
                .contentType(MediaType.APPLICATION_JSON);
        if (actor != null) req.header("X-LH-Actor", actor);
        if (body != null) req.body(body);
        return req.retrieve().body(JsonNode.class);
    }

    private JsonNode get(String uri, String actor) {
        var req = client().get().uri(uri);
        if (actor != null) req.header("X-LH-Actor", actor);
        return req.retrieve().body(JsonNode.class);
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Attende (polling, niente attese fisse) che la condizione diventi vera; fallisce allo scadere. */
    private void await(String what, java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            sleep(250);
        }
        throw new AssertionError("Timeout in attesa di: " + what);
    }

    private void awaitRunDone(String runId) {
        await("fine della run " + runId,
                () -> !get("/v1/demo/scenario-runs/" + runId, null).path("status").asString().equals("RUNNING"));
    }

    @Test
    void validateRuntimeEventContracts() throws Exception {
        // 1. Scenarios
        List<String> scenarios = List.of(
                "SCN-WEEKEND-ANNA", "SCN-MIXED-DAY", "SCN-REJECTS", "SCN-TIER-UP",
                "SCN-REFERRAL", "SCN-ONBOARDING", "SCN-DIGITAL", "SCN-POISON"
        );
        for (String scn : scenarios) {
            JsonNode run = post("/v1/demo/scenarios/" + scn + "/run", "ADMIN:test", null);
            awaitRunDone(run.path("runId").asString());
        }

        // 2. Redemption with approval flow
        post("/v1/portal/redemptions", null, Map.of("memberId", "MBR-000004", "rewardCode", "RWD-SHOP-10"));

        // 3. Contest play
        post("/v1/portal/contests/IW-AUTUNNO/play", null, Map.of("memberId", "MBR-000002"));

        // 4. Manual wallet adjustment
        post("/v1/wallets/MBR-000002/adjustments", "ADMIN:test", Map.of(
                "currency", "PTS", "direction", "CREDIT", "amount", 100, "reason", "GOODWILL", "note", "Test adjustment"
        ));

        // 5. Webhook test delivery
        JsonNode wh = post("/v1/webhooks", "ADMIN:test", Map.of(
                "code", "WH-CONTRACT-TEST", "name", "Test WH", "url", "https://example.org/hook", "factTypes", List.of("member.registered", "tier.upgraded", "wallet.points.earned")
        ));
        try {
            post("/v1/webhooks/WH-CONTRACT-TEST/test", "ADMIN:test", null);
        } catch (Exception ignored) {
            // Some requests might return 40x or 50x in test scenarios, we just want the delivery to be created.
        }

        // 6. Member registration + update + anonymization
        JsonNode newMember = post("/v1/members", "ADMIN:test", Map.of(
                "firstName", "Test", "lastName", "Test", "email", "test@test.com", "channel", "STORE"
        ));
        String newId = newMember.path("id").asString();
        long version = newMember.path("version").asLong();
        patch("/v1/members/" + newId, "ADMIN:test", Map.of(
                "version", version, "lastName", "Test Updated"
        ));
        post("/v1/members/" + newId + "/anonymize", "ADMIN:test", Map.of("confirm", newId));

        // 7. (elenco = array JSON) Abbina a mano l'ingresso UNMATCHED prodotto da SCN-MIXED-DAY (MBR-000404, docs/10 §8).
        await("riga UNMATCHED", () -> !get("/v1/inbound-events?status=UNMATCHED", "ADMIN:test").isEmpty());
        String rowId = get("/v1/inbound-events?status=UNMATCHED", "ADMIN:test").get(0).path("id").asString();
        post("/v1/inbound-events/" + rowId + "/match", "ADMIN:test", Map.of("memberId", "MBR-000002"));

        // 8. DLQ reprocess
        await("messaggio in DLQ (SCN-POISON)", () -> !get("/v1/dlq?size=100", "ADMIN:test").path("items").isEmpty());
        JsonNode dlqs = get("/v1/dlq?size=100", "ADMIN:test");
        if (dlqs.path("items").isArray() && !dlqs.path("items").isEmpty()) {
            String dlqId = dlqs.path("items").get(0).path("id").asString();
            post("/v1/dlq/" + dlqId + "/reprocess", "ADMIN:test", null);
        }

        // Attende che insight.event_store smetta di crescere (tutti gli eventi a valle registrati).
        JdbcClient jdbc = JdbcClient.create(dataSource);
        long[] last = {-1};
        await("event_store stabile", () -> {
            long n = jdbc.sql("SELECT count(*) FROM event_store").query(Long.class).single();
            boolean stable = n > 0 && n == last[0];
            last[0] = n;
            sleep(750);
            return stable;
        });
        List<String> payloads = jdbc.sql("SELECT payload::text FROM event_store").query(String.class).list();

        String rootDir = Paths.get("../../contracts/events").toAbsolutePath().normalize().toString();
        String envelopeSchema = Files.readString(Paths.get(rootDir, "envelope.schema.json"));
        JsonSchemaValidator validator = new JsonSchemaValidator();

        Set<String> declaredTypes = new HashSet<>();
        try (Stream<Path> paths = Files.walk(Paths.get(rootDir))) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".schema.json") && !p.toString().endsWith("envelope.schema.json"))
                    .forEach(p -> {
                        // <nome>.v<n>.schema.json è la versione n dello stesso type (docs/05 §9)
                        String name = p.getFileName().toString().replace(".schema.json", "").replaceFirst("\\.v\\d+$", "");
                        String family = p.getParent().getFileName().toString();
                        declaredTypes.add("io.loyaltyhub." + family + "." + name);
                    });
        }

        Set<String> coveredTypes = new HashSet<>();
        Map<String, String> schemas = new HashMap<>();

        for (String payload : payloads) {
            JsonNode env = mapper.readTree(payload);
            String type = env.path("type").asString();
            if (!type.startsWith("io.loyaltyhub.")) {
                continue;
            }
            String family = type.split("\\.")[2];
            String name = type.substring(type.indexOf(family) + family.length() + 1);

            String expectedTopic = "lh." + family + "s.v1";
            if (family.equals("audit") || family.equals("dlq")) {
                expectedTopic = "lh." + family + ".v1";
            }
            String actualTopic = jdbc.sql("SELECT topic FROM event_store WHERE event_id = ?").param(env.path("id").asText()).query(String.class).single();
            assertThat(actualTopic).as("Topic mapping for %s", type).isEqualTo(expectedTopic);

            List<String> envErrors = validator.validate("envelope", envelopeSchema, payload);
            assertThat(envErrors).as("Envelope errors for event %s of type %s", env.path("id").asText(), type).isEmpty();

            assertThat(env.path("lhcorrelationid").asText()).as("Missing correlationId in %s", type).isNotEmpty();

            if (type.startsWith("io.loyaltyhub.fact.member") || type.startsWith("io.loyaltyhub.action.member")) {
                assertThat(env.path("subject").asText()).as("Subject format for %s", type).startsWith("member:");
            }

            if (declaredTypes.contains(type)) {
                coveredTypes.add(type);
                String dataschema = env.path("dataschema").asText();
                String schemaVersion = dataschema.substring(dataschema.lastIndexOf(':') + 1);
                String schemaKey = type + ":" + schemaVersion;
                if (!schemas.containsKey(schemaKey)) {
                    String file = "1".equals(schemaVersion) ? name + ".schema.json" : name + ".v" + schemaVersion + ".schema.json";
                    schemas.put(schemaKey, Files.readString(Paths.get(rootDir, family, file)));
                }
                String dataJson = env.path("data").toString();
                List<String> dataErrors = validator.validate(type, schemas.get(schemaKey), dataJson);
                assertThat(dataErrors).as("Data errors for event %s of type %s", env.path("id").asText(), type).isEmpty();
            }
        }

        System.out.println("Tipi dichiarati ma mai prodotti:");
        Set<String> missing = new HashSet<>(declaredTypes);
        missing.removeAll(coveredTypes);
        missing.forEach(System.out::println);

        // Il test non deve passare "a vuoto": i flussi sopra producono almeno questi tipi, validati contro lo schema.
        assertThat(coveredTypes).contains(
                "io.loyaltyhub.fact.member.registered",
                "io.loyaltyhub.fact.member.updated",
                "io.loyaltyhub.fact.wallet.points.adjusted",
                "io.loyaltyhub.fact.wallet.points.earned",
                "io.loyaltyhub.fact.tier.upgraded",
                "io.loyaltyhub.fact.reward.redemption.requested");
    }
}
