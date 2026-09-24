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
 * M6.4 nel deployable consolidato: l'effetto {@code SEND_MESSAGE} (docs/03 §3.4, F-CMP-04) end-to-end. Un
 * {@code member.birthday} per Anna (BASE) dal simulatore → {@code CMP-BIRTHDAY} → 250 PTS nel wallet e l'effetto
 * {@code message.send} → messaggio "Buon compleanno, Anna!" nell'inbox (template {@code MSG-BIRTHDAY}), nello stesso
 * tracciato dell'azione. Rielaborare la stessa azione non duplica né i punti né il messaggio. Profilo {@code inproc}.
 */
@SpringBootTest(
        classes = HubApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.name=hub")
@ActiveProfiles({"demo", "inproc"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HubSendMessageIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String ANNA = "MBR-000001";

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
    void birthdayCampaignGrantsPointsAndDeliversTheMessage() {
        long before = walletPts(ANNA);
        long unreadBefore = unread(ANNA);

        JsonNode fired = client().post().uri("/v1/demo/simulator/fire")
                .header("X-LH-Actor", "ADMIN:test").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("memberId", ANNA, "type", "member.birthday", "data", Map.of("age", 28)))
                .retrieve().body(JsonNode.class);
        assertThat(fired.get(0).path("status").asString()).isEqualTo("ACCEPTED");
        String correlationId = fired.get(0).path("correlationId").asString();

        JsonNode message = awaitInbox(ANNA, m -> m.path("title").asString().equals("Buon compleanno, Anna!"));
        assertThat(message.path("category").asString()).isEqualTo("PROGRAM");
        assertThat(message.path("icon").asString()).isEqualTo("cake");
        assertThat(message.path("linkTarget").asString()).isEqualTo("/portal/activity");
        assertThat(message.path("read").asBoolean()).isFalse();

        // 250 PTS di CMP-BIRTHDAY (BASE: moltiplicatore di livello 1).
        long deadline = System.currentTimeMillis() + 25_000;
        long after = before;
        while (System.currentTimeMillis() < deadline && after != before + 250) {
            after = walletPts(ANNA);
            sleep(500);
        }
        assertThat(after).as("250 punti di compleanno").isEqualTo(before + 250);

        // Registro di BO-19: il messaggio nasce dall'effetto, nello stesso tracciato dell'azione.
        JsonNode log = client().get().uri("/v1/messages?memberId=" + ANNA + "&templateCode=MSG-BIRTHDAY")
                .retrieve().body(JsonNode.class);
        assertThat(log.path("items")).hasSize(1);
        JsonNode entry = log.path("items").get(0);
        assertThat(entry.path("sourceType").asString()).isEqualTo("message.send");
        assertThat(entry.path("channel").asString()).isEqualTo("INAPP");
        assertThat(entry.path("correlationId").asString()).isEqualTo(correlationId);

        sleep(2_000); // lascia arrivare anche la notifica dei punti (regola NR-POINTS-EARNED)
        assertThat(titles(ANNA)).containsOnlyOnce("Buon compleanno, Anna!");
        assertThat(unread(ANNA)).as("compleanno + punti guadagnati").isEqualTo(unreadBefore + 2);
    }

    // ---------- helper ----------

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

    private long walletPts(String memberId) {
        JsonNode w = client().get().uri("/v1/portal/wallets/" + memberId).retrieve().body(JsonNode.class);
        return w.path("balances").path("PTS").path("active").asLong();
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
