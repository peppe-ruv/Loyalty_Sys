package io.loyaltyhub.gamification;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5.2 giocata (docs/03 §6, docs/servizi/gamification-service.md §5, §7; F-IW-04, F-IW-05): crediti del seed, giocata
 * gratuita una volta al giorno, tetto giornaliero, membro non attivo, concorso non in corso, effetto {@code plays.grant}
 * idempotente, 50 giocate concorrenti con un solo istante scaduto → esattamente 1 {@code WIN}, fatti
 * {@code contest.played}/{@code contest.won} nello stesso tracciato. EmbeddedKafka + Zonky.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlayIT {

    private static final EmbeddedPostgres PG = startPg();

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
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "20");
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    void seededHistoryIsConsistent() {
        JsonNode portal = get("/v1/portal/contests?memberId=MBR-000010");
        assertThat(portal.size()).as("solo IW-AUTUNNO è in corso").isEqualTo(1);
        JsonNode autunno = portal.get(0);
        assertThat(autunno.path("code").asString()).isEqualTo("IW-AUTUNNO");
        assertThat(autunno.path("playsAvailable").asInt()).as("2 crediti CMP-SURVEY + la gratuita di oggi").isEqualTo(3);
        assertThat(autunno.path("credits").asInt()).isEqualTo(2);
        assertThat(autunno.path("freePlayAvailable").asBoolean()).isTrue();
        assertThat(autunno.path("prizes").get(0).has("quantityRemaining")).isFalse();

        JsonNode history = get("/v1/portal/contests/IW-AUTUNNO/plays?memberId=MBR-000010");
        assertThat(history.size()).isEqualTo(6);
        assertThat(count(history, "WIN")).isEqualTo(1);

        JsonNode estate = contest("IW-ESTATE");
        assertThat(estate.path("wins").asLong()).isEqualTo(142);
        assertThat(estate.path("instants").path("claimed").asLong()).isEqualTo(142);
        assertThat(estate.path("instants").path("voided").asLong()).isEqualTo(10);
        assertThat(estate.path("prizesRemaining").asInt()).isEqualTo(10);
        JsonNode stats = get("/v1/contests/IW-ESTATE/stats");
        assertThat(stats.path("winners").asLong()).isEqualTo(10);
        assertThat(stats.path("plays").asLong()).isEqualTo(142 * 4);
        JsonNode winners = get("/v1/contests/IW-ESTATE/winners");
        assertThat(winners.size()).isEqualTo(142);
        assertThat(winners.get(0).path("nickname").asString()).isNotBlank();
    }

    @Test
    void freePlayOnceADayThenNoPlays() {
        JsonNode first = play("IW-AUTUNNO", "MBR-000001", 200);
        assertThat(first.path("outcome").asString()).isIn("WIN", "LOSE");
        assertThat(first.path("playsAvailable").asInt()).isZero();
        assertThat(first.path("correlationId").asString()).isNotBlank();
        assertThat(play("IW-AUTUNNO", "MBR-000001", 422).path("code").asString()).isEqualTo("NO_PLAYS_AVAILABLE");
        assertThat(play("IW-AUTUNNO", "MBR-000008", 422).path("code").asString()).isEqualTo("MEMBER_NOT_ACTIVE");
        assertThat(play("IW-NATALE", "MBR-000009", 422).path("code").asString()).isEqualTo("CONTEST_NOT_LIVE");
        assertThat(status("POST", "/v1/portal/contests/IW-NOPE/play", Map.of("memberId", "MBR-000009"))).isEqualTo(404);
    }

    @Test
    void grantIsIdempotentAndDailyLimitHolds() throws Exception {
        String id = createLive("IW-IT-CAP", 5, false, 1);
        publishGrant("MBR-000003", "IW-IT-CAP", 3, "EFF-IT-CAP-1");
        publishGrant("MBR-000003", "IW-IT-CAP", 3, "EFF-IT-CAP-1");
        JsonNode c = awaitCredits("MBR-000003", "IW-IT-CAP", 1);
        assertThat(c.path("credits").asInt()).as("stesso effectId → un solo credito").isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM play_grant WHERE effect_id = 'EFF-IT-CAP-1'").query(Long.class).single()).isEqualTo(1);

        play("IW-IT-CAP", "MBR-000003", 200);
        assertThat(play("IW-IT-CAP", "MBR-000003", 422).path("code").asString()).isEqualTo("DAILY_LIMIT_REACHED");
        assertThat(factsFor("member:MBR-000003", "io.loyaltyhub.fact.contest.plays.granted")).hasSize(1);
        assertThat(id).isNotBlank();
    }

    @Test
    void fiftyConcurrentPlaysOneExpiredInstantOneWin() throws Exception {
        String id = createLive("IW-IT-RACE", 1, true, null);
        jdbc.sql("UPDATE winning_instant SET instant_at = ? WHERE contest_id = ?")
                .params(Timestamp.from(Instant.now().minusSeconds(1)), id).update();
        List<String> racers = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            String m = String.format("MBR-9%05d", i);
            racers.add(m);
            jdbc.sql("INSERT INTO gamification_member_snapshot (member_id, nickname, status) VALUES (?, ?, 'ACTIVE')")
                    .params(m, "racer" + i).update();
        }
        List<Callable<JsonNode>> calls = racers.stream().<Callable<JsonNode>>map(m -> () -> play("IW-IT-RACE", m, 200)).toList();
        List<JsonNode> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(25)) {
            for (Future<JsonNode> f : pool.invokeAll(calls)) {
                results.add(f.get());
            }
        }
        List<JsonNode> wins = results.stream().filter(r -> "WIN".equals(r.path("outcome").asString())).toList();
        assertThat(wins).as("un solo istante → una sola vincita").hasSize(1);
        assertThat(wins.get(0).path("prize").path("code").asString()).isEqualTo("PTS-10");
        JsonNode contest = contest("IW-IT-RACE");
        assertThat(contest.path("instants").path("claimed").asLong()).isEqualTo(1);
        assertThat(contest.path("prizesRemaining").asInt()).isZero();
        assertThat(contest.path("plays").asLong()).isEqualTo(50);

        String correlation = wins.get(0).path("correlationId").asString();
        String winnerSubject = "member:" + jdbc.sql("SELECT member_id FROM play WHERE contest_id = ? AND outcome = 'WIN'")
                .param(id).query(String.class).single();
        List<JsonNode> won = factsFor(winnerSubject, "io.loyaltyhub.fact.contest.won");
        assertThat(won).hasSize(1);
        assertThat(won.get(0).path("lhcorrelationid").asString()).isEqualTo(correlation);
        assertThat(won.get(0).path("data").path("points").asLong()).isEqualTo(10);
        assertThat(factsFor(winnerSubject, "io.loyaltyhub.fact.contest.played")).hasSize(1);
    }

    // ---------- helper ----------

    private String createLive(String code, int qty, boolean free, Integer cap) {
        Instant start = Instant.now().minus(Duration.ofHours(1));
        Map<String, Object> body = new HashMap<>(Map.of("code", code, "name", "Prova " + code, "mechanic", "WHEEL",
                "startAt", start.toString(), "endAt", start.plus(Duration.ofHours(3)).toString(), "freePlayDaily", free,
                "distribution", "UNIFORM", "prizes", List.of(Map.of("code", "PTS-10", "name", "10 punti", "type", "POINTS",
                        "points", 10, "quantity", qty))));
        if (cap != null) {
            body.put("maxPlaysPerMemberPerDay", cap);
        }
        String id = send("POST", "/v1/contests", "MARKETING:luca", body, 201).path("id").asString();
        send("POST", "/v1/contests/" + id + "/instants/generate", "MARKETING:luca", null, 200);
        // Istanti tutti nel futuro remoto per il test del tetto: nessuna vincita casuale.
        if (cap != null) {
            jdbc.sql("UPDATE winning_instant SET instant_at = ? WHERE contest_id = ?")
                    .params(Timestamp.from(Instant.now().plus(Duration.ofHours(2))), id).update();
        }
        // Ciclo di vita con approvazione (M7.1): revisione, approvazione LEGAL, pubblicazione.
        send("POST", "/v1/contests/" + id + "/transitions", "MARKETING:luca", Map.of("action", "SUBMIT"), 200);
        send("POST", "/v1/contests/" + id + "/transitions", "LEGAL:elena", Map.of("action", "APPROVE"), 200);
        send("POST", "/v1/contests/" + id + "/transitions", "MARKETING:luca", Map.of("action", "PUBLISH"), 200);
        return id;
    }

    private JsonNode awaitCredits(String memberId, String code, int atLeast) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            for (JsonNode c : get("/v1/portal/contests?memberId=" + memberId)) {
                if (code.equals(c.path("code").asString()) && c.path("credits").asInt() >= atLeast) {
                    Thread.sleep(1_000); // lascia arrivare l'eventuale doppione
                    for (JsonNode again : get("/v1/portal/contests?memberId=" + memberId)) {
                        if (code.equals(again.path("code").asString())) {
                            return again;
                        }
                    }
                }
            }
            Thread.sleep(300);
        }
        throw new AssertionError("crediti non arrivati per " + memberId);
    }

    private void publishGrant(String memberId, String contestCode, int count, String effectId) throws Exception {
        String id = "EVT-" + UUID.randomUUID();
        Map<String, Object> event = Map.of("specversion", "1.0", "id", id, "source", "urn:loyaltyhub:service:campaign",
                "type", "io.loyaltyhub.effect.plays.grant", "subject", "member:" + memberId,
                "time", Instant.now().toString(), "lhcorrelationid", id, "lhhop", 1,
                "data", Map.of("effectId", effectId, "campaignCode", "CMP-SURVEY", "actionId", "ACT-" + id,
                        "actionType", "survey.completed", "contestCode", contestCode, "count", count));
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class))) {
            producer.send(new ProducerRecord<>("lh.effects.v1", memberId, mapper.writeValueAsString(event))).get();
        }
    }

    private List<JsonNode> factsFor(String subject, String type) {
        List<JsonNode> out = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "group.id", "play-it-" + UUID.randomUUID(), "auto.offset.reset", "earliest",
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

    private static long count(JsonNode plays, String outcome) {
        long n = 0;
        for (JsonNode p : plays) {
            if (outcome.equals(p.path("outcome").asString())) n++;
        }
        return n;
    }

    private JsonNode contest(String code) {
        for (JsonNode c : get("/v1/contests")) {
            if (code.equals(c.path("code").asString())) {
                return c;
            }
        }
        throw new AssertionError("concorso assente: " + code);
    }

    private JsonNode play(String code, String memberId, int expected) {
        return send("POST", "/v1/portal/contests/" + code + "/play", "MEMBER:" + memberId, Map.of("memberId", memberId), expected);
    }

    private JsonNode get(String path) {
        return RestClient.create("http://localhost:" + port).get().uri(path).retrieve().body(JsonNode.class);
    }

    private int status(String method, String path, Object body) {
        var spec = RestClient.create("http://localhost:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return spec.exchange((req, res) -> res.getStatusCode().value());
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
