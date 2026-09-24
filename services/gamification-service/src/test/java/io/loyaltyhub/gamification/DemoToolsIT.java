package io.loyaltyhub.gamification;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5.7 aiuti demo di gamification (docs/servizi/gamification-service.md §3 "Demo", §5; F-IW-08, BO-14, BO-30):
 * istante piantato → la prossima giocata vince quel premio senza toccare il montepremi; solo {@code ADMIN}, solo
 * concorsi {@code LIVE}; job di fine concorso con {@code asOf} → {@code ENDED}, istanti aperti {@code VOID}, fatto
 * {@code contest.status.changed}. EmbeddedKafka + Zonky.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoToolsIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String ADMIN = "ADMIN:giuseppe";

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=gamification");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    @Order(1)
    void plantedInstantMakesTheNextPlayWin() {
        String contestId = contest("IW-AUTUNNO").path("id").asString();
        // Gli istanti aperti già passati vanno nel futuro: resta vincibile solo quello piantato.
        jdbc.sql("UPDATE winning_instant SET instant_at = ? WHERE contest_id = ? AND status = 'OPEN' AND instant_at <= ?")
                .params(Timestamp.from(Instant.now().plus(Duration.ofDays(1))), contestId,
                        Timestamp.from(Instant.now().plus(Duration.ofHours(1))))
                .update();
        JsonNode before = contest("IW-AUTUNNO");
        assertThat(before.path("instants").path("total").asLong()).isEqualTo(355);

        JsonNode planted = send("POST", "/v1/demo/contests/IW-AUTUNNO/plant-instant", ADMIN,
                Map.of("prizeCode", "PTS-100"), 200);
        String instantId = planted.path("instantId").asString();
        assertThat(instantId).isNotBlank();
        assertThat(planted.path("prizeCode").asString()).isEqualTo("PTS-100");
        assertThat(Instant.parse(planted.path("instantAt").asString())).isBefore(Instant.now());

        JsonNode play = send("POST", "/v1/portal/contests/IW-AUTUNNO/play", "MEMBER:MBR-000009",
                Map.of("memberId", "MBR-000009"), 200);
        assertThat(play.path("outcome").asString()).isEqualTo("WIN");
        assertThat(play.path("prize").path("code").asString()).isEqualTo("PTS-100");

        JsonNode instant = findInstant("IW-AUTUNNO", instantId);
        assertThat(instant.path("planted").asBoolean()).isTrue();
        assertThat(instant.path("status").asString()).isEqualTo("CLAIMED");
        assertThat(instant.path("claimedBy").asString()).isEqualTo("MBR-000009");

        JsonNode after = contest("IW-AUTUNNO");
        assertThat(after.path("instants").path("total").asLong()).as("montepremi invariato").isEqualTo(355);
        assertThat(after.path("prizesTotal").asInt()).isEqualTo(355);
        assertThat(after.path("instants").path("claimed").asLong())
                .isEqualTo(before.path("instants").path("claimed").asLong() + 1);
    }

    @Test
    @Order(2)
    void onlyAdminOnlyLiveOnlyKnownPrizes() {
        assertThat(send("POST", "/v1/demo/contests/IW-AUTUNNO/plant-instant", "MARKETING:luca",
                Map.of("prizeCode", "PTS-100"), 403)).isNotNull();
        assertThat(send("POST", "/v1/demo/contests/IW-NATALE/plant-instant", ADMIN,
                Map.of("prizeCode", "PTS-100"), 409).path("code").asString()).isEqualTo("CONTEST_NOT_LIVE");
        assertThat(send("POST", "/v1/demo/contests/IW-AUTUNNO/plant-instant", ADMIN,
                Map.of("prizeCode", "NOPE"), 422).path("code").asString()).isEqualTo("PRIZE_NOT_FOUND");
        assertThat(send("POST", "/v1/demo/jobs/close-contests", "MARKETING:luca", null, 403)).isNotNull();
    }

    @Test
    @Order(3)
    void closeContestsEndsLiveContestsAndVoidsOpenInstants() {
        JsonNode before = contest("IW-AUTUNNO");
        long open = before.path("instants").path("open").asLong();
        assertThat(open).isPositive();
        // Senza asOf (adesso) niente da chiudere: IW-AUTUNNO finisce tra 40 giorni.
        assertThat(send("POST", "/v1/demo/jobs/close-contests", ADMIN, null, 200).path("contests").asInt()).isZero();

        String asOf = Instant.now().plus(Duration.ofDays(60)).toString();
        JsonNode r = send("POST", "/v1/demo/jobs/close-contests?asOf=" + asOf, ADMIN, null, 200);
        assertThat(r.path("contests").asInt()).isEqualTo(1);
        assertThat(r.path("voided").asLong()).isEqualTo(open);

        JsonNode after = contest("IW-AUTUNNO");
        assertThat(after.path("status").asString()).isEqualTo("ENDED");
        assertThat(after.path("instants").path("open").asLong()).isZero();
        assertThat(after.path("instants").path("voided").asLong())
                .isEqualTo(before.path("instants").path("voided").asLong() + open);

        List<JsonNode> facts = factsFor("contest:IW-AUTUNNO", "io.loyaltyhub.fact.contest.status.changed");
        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).path("data").path("previousStatus").asString()).isEqualTo("LIVE");
        assertThat(facts.get(0).path("data").path("newStatus").asString()).isEqualTo("ENDED");
        assertThat(facts.get(0).path("lhactor").asString()).isEqualTo(ADMIN);

        // Idempotente: una seconda esecuzione non trova più nulla.
        assertThat(send("POST", "/v1/demo/jobs/close-contests?asOf=" + asOf, ADMIN, null, 200).path("contests").asInt()).isZero();
        // E il concorso chiuso non accetta più istanti piantati.
        assertThat(send("POST", "/v1/demo/contests/IW-AUTUNNO/plant-instant", ADMIN,
                Map.of("prizeCode", "PTS-100"), 409).path("code").asString()).isEqualTo("CONTEST_NOT_LIVE");
    }

    // ---------- helper ----------

    private JsonNode findInstant(String code, String instantId) {
        for (int page = 0; page < 10; page++) {
            JsonNode res = send("GET", "/v1/contests/" + code + "/instants?status=CLAIMED&size=100&page=" + page, ADMIN, null, 200);
            for (JsonNode i : res.path("items")) {
                if (instantId.equals(i.path("id").asString())) {
                    return i;
                }
            }
            if (res.path("items").size() < 100) {
                break;
            }
        }
        throw new AssertionError("istante assente: " + instantId);
    }

    private List<JsonNode> factsFor(String subject, String type) {
        List<JsonNode> out = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "group.id", "demo-tools-it-" + UUID.randomUUID(), "auto.offset.reset", "earliest",
                "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class))) {
            consumer.subscribe(List.of("lh.facts.v1"));
            long deadline = System.currentTimeMillis() + 10_000;
            long quietUntil = 0;
            while (System.currentTimeMillis() < deadline && (out.isEmpty() || System.currentTimeMillis() < quietUntil)) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(400))) {
                    JsonNode e = mapper.readTree(r.value());
                    if (subject.equals(e.path("subject").asString()) && type.equals(e.path("type").asString())) {
                        out.add(e);
                        quietUntil = System.currentTimeMillis() + 2_000;
                    }
                }
            }
        }
        return out;
    }

    private JsonNode contest(String code) {
        for (JsonNode c : RestClient.create("http://localhost:" + port).get().uri("/v1/contests").retrieve().body(JsonNode.class)) {
            if (code.equals(c.path("code").asString())) {
                return c;
            }
        }
        throw new AssertionError("concorso assente: " + code);
    }

    private JsonNode send(String method, String path, String actor, Object body, int expected) {
        var spec = RestClient.create("http://localhost:" + port).method(HttpMethod.valueOf(method))
                .uri(path).header("X-LH-Actor", actor);
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
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
