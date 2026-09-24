package io.loyaltyhub.hub;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6.0 nel deployable consolidato (docs/13 ADR-023, ADR-024; docs/servizi/engagement-service.md §7): engagement vive nel
 * JVM dell'hub con il suo schema e il suo gruppo consumer. Acquisto di Marco (130 € feriale, SILVER) → wallet emette
 * {@code wallet.points.earned} per PTS e STS → una sola notifica "Hai guadagnato 162 punti"; {@code SCN-TIER-UP} →
 * messaggi di livello e di badge per Giulia. Profilo {@code inproc}: nessun broker.
 */
@SpringBootTest(
        classes = HubApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.name=hub")
@ActiveProfiles({"demo", "inproc"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HubEngagementIT {

    private static final EmbeddedPostgres PG = startPg();

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
    void purchaseNotifiesThePointsOnceInTheInbox() {
        assertThat(unread("MBR-000002")).as("inbox seminata: 3 non letti").isEqualTo(3);
        Map<String, Object> event = Map.of(
                "specversion", "1.0", "id", "hub-engagement-01", "source", "urn:loyaltyhub:source:ecommerce",
                "type", "purchase.completed", "subject", "member:MBR-000002", "time", "2026-09-15T10:00:00Z",
                "data", Map.of("orderId", "ORD-ENG-1", "amount", 130, "currency", "EUR", "channel", "ONLINE"));
        client().post().uri("/v1/events").contentType(MediaType.APPLICATION_JSON).body(event).retrieve().body(JsonNode.class);

        JsonNode message = awaitInbox("MBR-000002", m -> m.path("title").asString().equals("Hai guadagnato 162 punti"));
        assertThat(message.path("body").asString()).contains("Marco");
        assertThat(message.path("read").asBoolean()).isFalse();
        sleep(2_000); // lascia arrivare l'eventuale notifica degli STS
        List<String> titles = titles("MBR-000002");
        assertThat(titles).as("una sola notifica: gli STS non ne producono").containsOnlyOnce("Hai guadagnato 162 punti")
                .doesNotContain("Hai guadagnato 130 punti");
        assertThat(unread("MBR-000002")).isEqualTo(4);
    }

    @Test
    void tierUpScenarioSendsLevelAndBadgeMessages() {
        JsonNode started = client().post().uri("/v1/demo/scenarios/SCN-TIER-UP/run")
                .header("X-LH-Actor", "ADMIN:test").retrieve().body(JsonNode.class);
        assertThat(started.path("runId").asString()).isNotBlank();
        awaitInbox("MBR-000003", m -> m.path("title").asString().equals("Sei salito di livello: ora sei GOLD"));
        awaitInbox("MBR-000003", m -> m.path("title").asString().equals("Nuovo badge: Tris"));
        JsonNode log = client().get().uri("/v1/messages?memberId=MBR-000003&category=TIER").retrieve().body(JsonNode.class);
        assertThat(log.path("items").get(0).path("sourceType").asString()).isEqualTo("tier.upgraded");
    }

    private JsonNode awaitInbox(String memberId, Predicate<JsonNode> match) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            for (JsonNode m : client().get().uri("/v1/portal/inbox?size=50&memberId=" + memberId).retrieve().body(JsonNode.class)
                    .path("items")) {
                if (match.test(m)) {
                    return m;
                }
            }
            sleep(500);
        }
        throw new AssertionError("messaggio non arrivato per " + memberId + ": " + titles(memberId));
    }

    private List<String> titles(String memberId) {
        List<String> out = new ArrayList<>();
        client().get().uri("/v1/portal/inbox?size=50&memberId=" + memberId).retrieve().body(JsonNode.class)
                .path("items").forEach(m -> out.add(m.path("title").asString()));
        return out;
    }

    private long unread(String memberId) {
        return client().get().uri("/v1/portal/inbox/unread-count?memberId=" + memberId).retrieve().body(JsonNode.class)
                .path("unread").asLong();
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
