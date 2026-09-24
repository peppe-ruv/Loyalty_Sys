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
 * M6.6 nel deployable consolidato (docs/12 §M6: "un contenuto riservato a SEG-DIGITAL compare a Marco dopo SCN-DIGITAL
 * e ricalcolo"). member possiede i segmenti e ne annuncia le appartenenze con {@code member.segment.entered/left};
 * engagement (contenuti), campaign (pubblico) e reward (visibilità) le leggono solo dai propri snapshot.
 * SCN-DIGITAL → etichette {@code ebill}+{@code directdebit} a Marco → ricalcolo (job demo) → Marco entra in SEG-DIGITAL
 * ed esce da SEG-NOT-EBILL → la griglia della home mostra {@code CNT-DIGITAL-THANKS} al posto di {@code CNT-EBILL}.
 * Profilo {@code inproc}: nessun broker.
 */
@SpringBootTest(
        classes = HubApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=hub", "loyaltyhub.member.segments.reannounce-delay-ms=5000"})
@ActiveProfiles({"demo", "inproc"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HubSegmentsIT {

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
    void scnDigitalAndRecomputeShowTheSegDigitalContentToMarco() {
        // Seed: le appartenenze arrivano agli snapshot degli altri servizi (anche se si sono azzerati dopo member, Q-81).
        assertThat(awaitGrid("MBR-000004", g -> g.contains("CNT-DIGITAL-THANKS"))).as("Davide è digitale dai seed")
                .contains("CNT-DIGITAL-THANKS").doesNotContain("CNT-EBILL");
        assertThat(awaitGrid("MBR-000002", g -> g.contains("CNT-EBILL"))).as("Marco non ha ancora la bolletta digitale")
                .contains("CNT-EBILL").doesNotContain("CNT-DIGITAL-THANKS");

        JsonNode started = client().post().uri("/v1/demo/scenarios/SCN-DIGITAL/run").header("X-LH-Actor", ADMIN)
                .retrieve().body(JsonNode.class);
        assertThat(started.path("runId").asString()).isNotBlank();
        long deadline = System.currentTimeMillis() + 30_000;
        List<String> labels = List.of();
        while (System.currentTimeMillis() < deadline && labels.size() < 2) {
            labels = new ArrayList<>();
            for (JsonNode l : client().get().uri("/v1/members/MBR-000002").retrieve().body(JsonNode.class).path("labels")) {
                labels.add(l.asString());
            }
            sleep(500);
        }
        assertThat(labels).containsExactlyInAnyOrder("ebill", "directdebit");
        // Il ricalcolo non è automatico in demo (docs/servizi/member-service.md §5): senza, nulla cambia.
        assertThat(grid("MBR-000002")).doesNotContain("CNT-DIGITAL-THANKS");

        JsonNode job = client().post().uri("/v1/demo/jobs/refresh-segments").header("X-LH-Actor", ADMIN)
                .retrieve().body(JsonNode.class);
        assertThat(job.path("entered").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(job.path("left").asInt()).isGreaterThanOrEqualTo(1);
        List<String> codes = new ArrayList<>();
        client().get().uri("/v1/members/MBR-000002/segments").retrieve().body(JsonNode.class)
                .forEach(s -> codes.add(s.path("code").asString()));
        assertThat(codes).contains("SEG-DIGITAL").doesNotContain("SEG-NOT-EBILL");

        List<String> grid = awaitGrid("MBR-000002", g -> g.contains("CNT-DIGITAL-THANKS") && !g.contains("CNT-EBILL"));
        assertThat(grid).as("M6: il contenuto riservato a SEG-DIGITAL compare a Marco").contains("CNT-DIGITAL-THANKS")
                .doesNotContain("CNT-EBILL");
    }

    @Test
    void segmentAudiencesReachCampaignsAndRewards() {
        // campaign: CMP-REVIEW ha pubblico SEG-AT-RISK (Stefano, fermo da 60 giorni).
        String reviewId = null;
        for (JsonNode c : client().get().uri("/v1/campaigns").retrieve().body(JsonNode.class)) {
            if ("CMP-REVIEW".equals(c.path("code").asString())) {
                reviewId = c.path("id").asString();
            }
        }
        assertThat(reviewId).isNotNull();
        String id = reviewId;
        long deadline = System.currentTimeMillis() + 45_000;
        while (System.currentTimeMillis() < deadline && "AUDIENCE".equals(reviewReason(id, "MBR-000006"))) {
            sleep(500);
        }
        assertThat(reviewReason(id, "MBR-000006")).as("Stefano è nel pubblico SEG-AT-RISK").isNotEqualTo("AUDIENCE");
        assertThat(reviewReason(id, "MBR-000003")).as("Giulia no").isEqualTo("AUDIENCE");

        // reward: RWD-EBIKE-RENT è per SEG-TORINO (Davide).
        deadline = System.currentTimeMillis() + 45_000;
        while (System.currentTimeMillis() < deadline && !catalog("MBR-000004").contains("RWD-EBIKE-RENT")) {
            sleep(500);
        }
        assertThat(catalog("MBR-000004")).contains("RWD-EBIKE-RENT");
        assertThat(catalog("MBR-000003")).doesNotContain("RWD-EBIKE-RENT");
    }

    // ---------- helper ----------

    private String reviewReason(String campaignId, String memberId) {
        Map<String, Object> body = Map.of(
                "action", Map.of("type", "review.submitted", "data", Map.of("productId", "SKU-1", "rating", 5)),
                "memberId", memberId, "campaignIds", List.of(campaignId));
        JsonNode r = client().post().uri("/v1/campaigns/simulate").contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(JsonNode.class).path("results").get(0);
        return r.path("matched").asBoolean() ? "MATCHED" : r.path("reason").asString();
    }

    private List<String> catalog(String memberId) {
        List<String> out = new ArrayList<>();
        for (JsonNode band : client().get().uri("/v1/portal/catalog?memberId=" + memberId).retrieve().body(JsonNode.class).path("bands")) {
            band.path("rewards").forEach(r -> out.add(r.path("code").asString()));
        }
        return out;
    }

    private List<String> grid(String memberId) {
        List<String> out = new ArrayList<>();
        client().get().uri("/v1/portal/content?placement=HOME_GRID&memberId=" + memberId).retrieve().body(JsonNode.class)
                .forEach(c -> out.add(c.path("code").asString()));
        return out;
    }

    private List<String> awaitGrid(String memberId, Predicate<List<String>> done) {
        long deadline = System.currentTimeMillis() + 45_000;
        List<String> g = grid(memberId);
        while (!done.test(g) && System.currentTimeMillis() < deadline) {
            sleep(500);
            g = grid(memberId);
        }
        return g;
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
