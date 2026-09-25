package io.loyaltyhub.hub;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M7.3 nel deployable consolidato, profilo {@code inproc} (nessun broker): {@code SCN-POISON} (docs/10 §8) → il motore
 * campagne rifiuta l'azione col flag demo {@code _poison} → il bus in-process la manda in DLQ con gli header
 * {@code lh-*} → insight apre la voce (BO-27) e il tracciato è {@code FAILED} (insight §7). Il <em>riprocessa</em>
 * passa davvero da {@code ingestion POST /v1/events} (ADR-002 eccezione 1, stesso processo): l'azione ripubblicata
 * con lo stesso id torna a campaign, che fallisce di nuovo (è avvelenata) e apre una nuova voce; poi la si scarta.
 */
@SpringBootTest(
        classes = HubApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.name=hub")
@ActiveProfiles({"demo", "inproc"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HubDlqIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String ADMIN = "ADMIN:marta.admin";

    @Value("${local.server.port}")
    private int port;

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

    @Test
    void scnPoisonLandsInDlqWithFailedTraceAndCanBeReprocessedAndDiscarded() {
        JsonNode started = client().post().uri("/v1/demo/scenarios/SCN-POISON/run").header("X-LH-Actor", ADMIN)
                .retrieve().body(JsonNode.class);
        JsonNode run = awaitRunDone(started.path("runId").asString());
        JsonNode step = run.path("results").get(0);
        assertThat(step.path("status").asString()).isEqualTo("ACCEPTED");
        String eventId = step.path("eventId").asString();
        String correlationId = step.path("correlationId").asString();

        // Voce DLQ del consumer lh-campaign con il codice del flag demo (non ritentabile: 1 tentativo).
        List<JsonNode> entries = awaitEntries(eventId, 1);
        assertThat(entries).hasSize(1);
        JsonNode entry = entries.get(0);
        assertThat(entry.path("consumer").asString()).isEqualTo("lh-campaign");
        assertThat(entry.path("errorCode").asString()).isEqualTo("DEMO_POISON");
        assertThat(entry.path("originalTopic").asString()).isEqualTo("lh.actions.v1");
        assertThat(entry.path("attempts").asInt()).isEqualTo(1);
        assertThat(entry.path("family").asString()).isEqualTo("ACTION");
        assertThat(entry.path("correlationId").asString()).isEqualTo(correlationId);

        // insight §7: tracciato FAILED.
        assertThat(awaitTraceStatus(correlationId, "FAILED")).isEqualTo("FAILED");

        // Riprocessa (ADMIN) → ingestion ripubblica lo stesso id → campaign fallisce ancora → nuova voce aperta.
        ResponseEntity<JsonNode> reprocessed = post("/v1/dlq/" + entry.path("id").asString() + "/reprocess", Map.of());
        assertThat(reprocessed.getStatusCode().value()).as(String.valueOf(reprocessed.getBody())).isEqualTo(200);
        assertThat(reprocessed.getBody().path("status").asString()).isEqualTo("REPROCESSED");
        List<JsonNode> after = awaitEntries(eventId, 2);
        assertThat(after).hasSize(2);
        JsonNode reopened = after.stream().filter(e -> "OPEN".equals(e.path("status").asString())).findFirst().orElseThrow();
        assertThat(reopened.path("correlationId").asString()).as("stesso tracciato").isEqualTo(correlationId);
        assertThat(awaitTraceStatus(correlationId, "FAILED")).isEqualTo("FAILED");

        // Scarta con nota.
        ResponseEntity<JsonNode> discarded = post("/v1/dlq/" + reopened.path("id").asString() + "/discard",
                Map.of("note", "Messaggio di prova avvelenato"));
        assertThat(discarded.getStatusCode().value()).isEqualTo(200);
        assertThat(discarded.getBody().path("status").asString()).isEqualTo("DISCARDED");
    }

    // ---------- helper ----------

    private List<JsonNode> awaitEntries(String eventId, int expected) {
        long deadline = System.currentTimeMillis() + 30_000;
        List<JsonNode> found = new ArrayList<>();
        while (System.currentTimeMillis() < deadline) {
            found = new ArrayList<>();
            for (JsonNode i : client().get().uri("/v1/dlq?size=200").retrieve().body(JsonNode.class).path("items")) {
                if (eventId.equals(i.path("eventId").asString())) {
                    found.add(i);
                }
            }
            if (found.size() >= expected) {
                return found;
            }
            sleep();
        }
        return found;
    }

    private String awaitTraceStatus(String correlationId, String status) {
        long deadline = System.currentTimeMillis() + 30_000;
        String current = null;
        while (System.currentTimeMillis() < deadline) {
            JsonNode trace = client().get().uri("/v1/traces/" + correlationId).exchange((req, res) -> res.getStatusCode().value() == 200
                    ? new tools.jackson.databind.ObjectMapper().readTree(res.getBody()) : null);
            current = trace == null ? null : trace.path("status").asString();
            if (status.equals(current)) {
                return current;
            }
            sleep();
        }
        return current;
    }

    private JsonNode awaitRunDone(String runId) {
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode run = null;
        while (System.currentTimeMillis() < deadline) {
            run = client().get().uri("/v1/demo/scenario-runs/" + runId).retrieve().body(JsonNode.class);
            if (!run.path("status").asString().equals("RUNNING")) {
                return run;
            }
            sleep();
        }
        return run;
    }

    private ResponseEntity<JsonNode> post(String path, Object body) {
        return client().post().uri(path).header("X-LH-Actor", ADMIN).contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().onStatus(HttpStatusCode::isError, (req, res) -> {
                }).toEntity(JsonNode.class);
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private static void sleep() {
        try {
            Thread.sleep(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
