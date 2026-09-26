package io.loyaltyhub.hub;

import io.loyaltyhub.ingestion.domain.ScenarioTime;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-PLT §FRP — l'hub su un broker Kafka reale (profilo {@code demo} senza {@code inproc}, ADR-025) con
 * l'inizializzazione lazy del profilo {@code free} (docs/06 §6, docs/11 §6): i listener e gli scheduler annotati
 * {@code @Lazy(false)} (docs/06 §5) partono comunque e un acquisto diventa punti. Senza Docker: EmbeddedKafka + Zonky.
 */
@SpringBootTest(
        classes = HubApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=hub", "spring.main.lazy-initialization=true"})
@EmbeddedKafka(partitions = 2, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestbookPltFreeProfileIT {

    private static final EmbeddedPostgres PG = TestbookE2eSupportIT.startPg();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestbookE2eSupportIT.datasource(registry, PG);
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @AfterAll
    void stop() throws Exception {
        PG.close();
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/plt/free-profile.csv", numLinesToSkip = 1)
    void freeProfile(String id, String description, String kase, String expected) {
        String got = switch (kase) {
            case "health" -> {
                JsonNode h = get("/actuator/health");
                yield h.path("status").asString() + " kafka=" + h.path("components").path("kafka").path("status").asString()
                        + " " + h.path("components").path("kafka").path("details").path("mode").asString();
            }
            case "purchase" -> {
                long before = pts("MBR-000002");
                String weekday = ScenarioTime.resolve("@lastWeekdayT10:00", Instant.now()).toString();
                JsonNode accepted = client().post().uri("/v1/events").contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("specversion", "1.0", "id", "tb-plt-free-" + System.nanoTime(),
                                "source", "urn:loyaltyhub:source:ecommerce", "type", "purchase.completed",
                                "subject", "member:MBR-000002", "time", weekday,
                                "data", Map.of("orderId", "ORD-TBPLT-FREE", "amount", 130, "currency", "EUR", "channel", "ONLINE")))
                        .retrieve().body(JsonNode.class);
                long deadline = System.currentTimeMillis() + 30_000;
                long now = pts("MBR-000002");
                while (now != before + 162 && System.currentTimeMillis() < deadline) {
                    pause();
                    now = pts("MBR-000002");
                }
                yield accepted.path("status").asString() + " +" + (now - before);
            }
            case "welcome" -> {
                JsonNode m = client().method(HttpMethod.POST).uri("/v1/members").contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("firstName", "Libera", "lastName", "Prova", "email", "tb.plt.free." + System.nanoTime()
                                + "@example.org", "channel", "PORTAL")).retrieve().body(JsonNode.class);
                String id2 = m.path("id").asString();
                long deadline = System.currentTimeMillis() + 30_000;
                long points = 0;
                while (points == 0 && System.currentTimeMillis() < deadline) {
                    pause();
                    points = walletPtsOrZero(id2);
                }
                yield "benvenuto=" + points;
            }
            default -> throw new IllegalArgumentException(kase);
        };
        assertThat(got).as("%s: %s", id, description).isEqualTo(expected);
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private JsonNode get(String path) {
        return client().get().uri(path).exchange((req, res) ->
                new tools.jackson.databind.ObjectMapper().readTree(new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    private long pts(String memberId) {
        return get("/v1/portal/wallets/" + memberId).path("balances").path("PTS").path("active").asLong();
    }

    private long walletPtsOrZero(String memberId) {
        JsonNode w = get("/v1/portal/wallets/" + memberId);
        return w.path("balances").path("PTS").path("active").asLong(0);
    }

    private static void pause() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
