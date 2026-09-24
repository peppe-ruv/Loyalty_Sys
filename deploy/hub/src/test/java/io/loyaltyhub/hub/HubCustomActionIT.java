package io.loyaltyhub.hub;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Accettazione M6 sui tipi custom (docs/12 §M6: "un tipo azione custom creato da BO-09 è inviabile da BO-28,
 * selezionabile in BO-06 e produce punti senza ridistribuire nulla") più gli attributi personalizzati nelle condizioni
 * (M6.7, F-ING-06, F-MBR-03). Tutto a caldo, nello stesso processo: tipo creato → i suoi campi alimentano il costruttore
 * di condizioni → campagna LIVE su quel trigger con {@code data.reading} e {@code member.attributes.hasGasContract} →
 * azione dal simulatore → punti. Poi l'attributo cambiato su un membro (BO-03) vale dalla sua azione successiva.
 * Profilo {@code inproc}: nessun broker.
 */
@SpringBootTest(
        classes = HubApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=hub"})
@ActiveProfiles({"demo", "inproc"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HubCustomActionIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String MARKETING = "MARKETING:luca.marketing";
    private static final String CARE = "CARE:paolo.care";

    private final ObjectMapper mapper = new ObjectMapper();

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
    void customActionTypeFromBo09EarnsPointsWithAttributeConditions() {
        // BO-09: nuovo tipo custom con i campi a righe.
        send(HttpMethod.POST, "/v1/event-types", MARKETING, Map.of(
                "code", "meter.reading.sent", "name", "Autolettura del contatore", "category", "SERVICE",
                "dataSchema", Map.of("type", "object", "required", List.of("reading"), "properties", Map.of(
                        "reading", Map.of("type", "number"),
                        "channel", Map.of("type", "string", "enum", List.of("APP", "WEB")))),
                "sampleData", Map.of("reading", 4521, "channel", "APP")), 201);

        // BO-06: il tipo è tra i trigger e i suoi campi tra quelli delle condizioni; gli attributi pure.
        assertThat(send(HttpMethod.GET, "/v1/event-types", null, null, 200).toString()).contains("meter.reading.sent");
        assertThat(send(HttpMethod.GET, "/v1/event-types/meter.reading.sent/fields", null, null, 200).toString())
                .contains("data.reading", "data.channel");
        assertThat(send(HttpMethod.GET, "/v1/attribute-definitions", null, null, 200).toString()).contains("hasGasContract");

        JsonNode campaign = send(HttpMethod.POST, "/v1/campaigns", MARKETING, Map.ofEntries(
                Map.entry("code", "CMP-IT-METER"),
                Map.entry("name", "Autolettura premiata"),
                Map.entry("memberDescription", "75 punti per ogni autolettura"),
                Map.entry("priority", 100),
                Map.entry("visibleInPortal", true),
                Map.entry("triggerActionTypes", List.of("meter.reading.sent")),
                Map.entry("audience", Map.of("all", true)),
                Map.entry("conditions", Map.of("op", "all", "rules", List.of(
                        Map.of("field", "data.reading", "cmp", "gt", "value", 0),
                        Map.of("field", "member.attributes.hasGasContract", "cmp", "eq", "value", true)))),
                Map.entry("effects", List.of(Map.of("type", "GRANT_POINTS", "currency", "PTS", "mode", "FIXED",
                        "value", 75, "tierMultiplierApplies", false))),
                Map.entry("limits", Map.of()),
                Map.entry("schedule", Map.of("startAt", "2026-01-01T00:00:00Z"))), 201);
        send(HttpMethod.POST, "/v1/campaigns/" + campaign.path("id").asString() + "/transitions", MARKETING,
                Map.of("action", "PUBLISH"), 200);

        // BO-28: Marco ha il contratto gas (seed) → +75; Giulia no → nulla.
        long marco = walletPts("MBR-000002");
        long giulia = walletPts("MBR-000003");
        assertThat(fire("MBR-000002")).isEqualTo("ACCEPTED");
        assertThat(awaitPts("MBR-000002", v -> v == marco + 75)).isEqualTo(marco + 75);
        assertThat(fire("MBR-000003")).isEqualTo("ACCEPTED");
        sleep(3000);
        assertThat(walletPts("MBR-000003")).as("condizione sull'attributo falsa").isEqualTo(giulia);

        // BO-03: l'attributo cambiato su Giulia arriva allo snapshot di campaign con member.updated.
        JsonNode member = send(HttpMethod.GET, "/v1/members/MBR-000003", null, null, 200);
        send(HttpMethod.PATCH, "/v1/members/MBR-000003", CARE, Map.of("version", member.path("version").asLong(),
                "attributes", Map.of("hasGasContract", true)), 200);
        long deadline = System.currentTimeMillis() + 30_000;
        while (walletPts("MBR-000003") == giulia && System.currentTimeMillis() < deadline) {
            fire("MBR-000003");
            sleep(1500);
        }
        assertThat(walletPts("MBR-000003")).isGreaterThanOrEqualTo(giulia + 75);
    }

    private String fire(String memberId) {
        JsonNode r = send(HttpMethod.POST, "/v1/demo/simulator/fire", MARKETING,
                Map.of("memberId", memberId, "type", "meter.reading.sent"), 200);
        return r.get(0).path("status").asString();
    }

    private long awaitPts(String memberId, LongPredicate done) {
        long deadline = System.currentTimeMillis() + 30_000;
        long v = walletPts(memberId);
        while (!done.test(v) && System.currentTimeMillis() < deadline) {
            sleep(500);
            v = walletPts(memberId);
        }
        return v;
    }

    private long walletPts(String memberId) {
        JsonNode w = client().get().uri("/v1/portal/wallets/" + memberId).retrieve().body(JsonNode.class);
        return w.path("balances").path("PTS").path("active").asLong();
    }

    private JsonNode send(HttpMethod method, String path, String actor, Object body, int expected) {
        var spec = client().method(method).uri(path);
        if (actor != null) {
            spec = spec.header("X-LH-Actor", actor);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes());
            assertThat(res.getStatusCode().value()).as(method + " " + path + " → " + text).isEqualTo(expected);
            return text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text);
        });
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
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
