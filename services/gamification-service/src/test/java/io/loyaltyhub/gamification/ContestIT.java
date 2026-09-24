package io.loyaltyhub.gamification;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5.1 concorsi instant win (docs/servizi/gamification-service.md §3, §5, §7; F-IW-01..03, F-IW-07): seed con istanti
 * rigenerati dal seme, stesso seme → stessi istanti, istanti visibili solo ad ADMIN/LEGAL, pubblicazione solo con
 * istanti generati, blocco di premi e istanti dal LIVE, fine concorso → istanti aperti annullati. EmbeddedKafka + Zonky.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContestIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final ZoneId ROME = ZoneId.of("Europe/Rome");

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

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
    void seededContestsHaveTheirInstants() {
        JsonNode autunno = contest("IW-AUTUNNO");
        assertThat(autunno.path("status").asString()).isEqualTo("LIVE");
        assertThat(autunno.path("seed").asLong()).isEqualTo(20260901L);
        assertThat(autunno.path("instants").path("total").asLong()).isEqualTo(355);
        assertThat(autunno.path("prizesTotal").asInt()).isEqualTo(355);
        assertThat(autunno.path("prizes").size()).isEqualTo(4);

        JsonNode natale = contest("IW-NATALE");
        assertThat(natale.path("status").asString()).isEqualTo("IN_REVIEW");
        assertThat(natale.path("instants").path("total").asLong()).isEqualTo(363);

        JsonNode estate = contest("IW-ESTATE");
        assertThat(estate.path("status").asString()).isEqualTo("ENDED");
        assertThat(estate.path("mechanic").asString()).isEqualTo("BOX");
        assertThat(estate.path("instants").path("open").asLong()).isZero();
        assertThat(estate.path("instants").path("claimed").asLong()).as("storico: 142 vincite").isEqualTo(142);
        assertThat(estate.path("instants").path("voided").asLong()).isEqualTo(10);

        // Istogramma per giorno: visibile anche a chi configura il concorso; somma = istanti totali.
        JsonNode histogram = send("GET", "/v1/contests/IW-AUTUNNO/instants/histogram", "MARKETING:luca", null, 200);
        long sum = 0;
        for (JsonNode d : histogram.path("days")) {
            sum += d.path("total").asLong();
        }
        assertThat(sum).isEqualTo(355);
        assertThat(histogram.path("distribution").asString()).isEqualTo("BUSINESS_HOURS");

        // BUSINESS_HOURS: tutti gli istanti tra le 8 e le 22 (Europe/Rome).
        for (JsonNode i : allInstants(autunno.path("id").asString())) {
            int hour = Instant.parse(i.path("instantAt").asString()).atZone(ROME).getHour();
            assertThat(hour).isBetween(8, 21);
        }
    }

    @Test
    void instantsTableIsReservedToAdminAndLegal() {
        String path = "/v1/contests/IW-AUTUNNO/instants";
        assertThat(status("GET", path, "MARKETING:luca", null)).isEqualTo(403);
        assertThat(status("GET", path, "CARE:paolo", null)).isEqualTo(403);
        assertThat(status("GET", path, "ANALYST:sara", null)).isEqualTo(403);
        assertThat(send("GET", path + "?size=10", "LEGAL:elena", null, 200).path("page").path("totalItems").asLong()).isEqualTo(355);
        assertThat(send("GET", path + "?size=10", "ADMIN:test", null, 200).path("items").size()).isEqualTo(10);
    }

    @Test
    void sameSeedGivesTheSameInstants() {
        String id = create("IW-IT-SEED", 42L).path("id").asString();
        JsonNode first = send("POST", "/v1/contests/" + id + "/instants/generate", "MARKETING:luca", Map.of("seed", 42), 200);
        assertThat(first.path("instants").asInt()).isEqualTo(13);
        assertThat(first.path("seed").asLong()).isEqualTo(42L);
        List<String> a = signature(allInstants(id));

        send("POST", "/v1/contests/" + id + "/instants/generate", "MARKETING:luca", Map.of("seed", 42), 200);
        assertThat(signature(allInstants(id))).as("stesso seme → stessi istanti").isEqualTo(a);

        send("POST", "/v1/contests/" + id + "/instants/generate", "MARKETING:luca", Map.of("seed", 7), 200);
        assertThat(signature(allInstants(id))).isNotEqualTo(a);
        assertThat(status("POST", "/v1/contests/" + id + "/instants/generate", "CARE:paolo", Map.of())).isEqualTo(403);
    }

    @Test
    void publishRequiresInstantsAndLiveLocksPrizes() {
        String id = create("IW-IT-LIFE", null).path("id").asString();
        // M7.1: un concorso si pubblica solo dopo l'approvazione LEGAL (docs/06 §7).
        send("POST", "/v1/contests/" + id + "/transitions", "MARKETING:luca", Map.of("action", "SUBMIT"), 200);
        send("POST", "/v1/contests/" + id + "/transitions", "LEGAL:elena", Map.of("action", "APPROVE"), 200);
        assertThat(send("POST", "/v1/contests/" + id + "/transitions", "MARKETING:luca", Map.of("action", "PUBLISH"), 422)
                .path("code").asString()).isEqualTo("INSTANTS_NOT_GENERATED");

        send("POST", "/v1/contests/" + id + "/instants/generate", "MARKETING:luca", null, 200);
        // Cambiare il montepremi prima del LIVE invalida gli istanti: vanno rigenerati.
        JsonNode edited = send("PUT", "/v1/contests/" + id, "MARKETING:luca", Map.of("prizes", List.of(
                prize("PTS-10", "POINTS", 10, null, 4))), 200);
        assertThat(edited.path("instantsGeneratedAt").isMissingNode() || edited.path("instantsGeneratedAt").isNull()).isTrue();
        assertThat(edited.path("instants").path("total").asLong()).isZero();
        assertThat(send("POST", "/v1/contests/" + id + "/transitions", "MARKETING:luca", Map.of("action", "PUBLISH"), 422)
                .path("code").asString()).isEqualTo("INSTANTS_NOT_GENERATED");
        send("POST", "/v1/contests/" + id + "/instants/generate", "MARKETING:luca", null, 200);

        JsonNode live = send("POST", "/v1/contests/" + id + "/transitions", "MARKETING:luca", Map.of("action", "PUBLISH"), 200);
        assertThat(live.path("status").asString()).isEqualTo("LIVE");
        assertThat(send("PUT", "/v1/contests/" + id, "MARKETING:luca", Map.of("prizes", List.of(
                prize("PTS-10", "POINTS", 10, null, 9))), 409).path("code").asString()).isEqualTo("CONTEST_LIVE_LOCKED");
        assertThat(send("PUT", "/v1/contests/" + id, "MARKETING:luca", Map.of("endAt", Instant.now().plus(Duration.ofDays(90)).toString()), 409)
                .path("code").asString()).isEqualTo("CONTEST_LIVE_LOCKED");
        assertThat(send("POST", "/v1/contests/" + id + "/instants/generate", "ADMIN:test", null, 409)
                .path("code").asString()).isEqualTo("INSTANTS_LOCKED");
        assertThat(send("PUT", "/v1/contests/" + id, "MARKETING:luca", Map.of("name", "Ruota di prova (rinominata)"), 200)
                .path("name").asString()).isEqualTo("Ruota di prova (rinominata)");

        JsonNode ended = send("POST", "/v1/contests/" + id + "/transitions", "MARKETING:luca", Map.of("action", "END"), 200);
        assertThat(ended.path("status").asString()).isEqualTo("ENDED");
        assertThat(ended.path("instants").path("open").asLong()).isZero();
        assertThat(ended.path("instants").path("voided").asLong()).isEqualTo(4);

        assertThat(factTypesFor("contest:IW-IT-LIFE")).contains("io.loyaltyhub.fact.contest.status.changed");
    }

    /**
     * Accettazione M7 (docs/12 §M7): {@code luca.marketing} non porta un concorso a LIVE, solo *Invia in revisione*;
     * {@code elena.legal} rifiuta (commento obbligatorio, 422 senza) e poi approva con commento; storico visibile;
     * coda approvazioni nel formato comune, anche «inviate da me».
     */
    @Test
    void marketingSubmitsLegalDecidesAndHistoryIsVisible() {
        String id = create("IW-IT-GOV", null).path("id").asString();
        send("POST", "/v1/contests/" + id + "/instants/generate", "MARKETING:luca", null, 200);
        String path = "/v1/contests/" + id + "/transitions";

        assertThat(send("POST", path, "MARKETING:luca", Map.of("action", "PUBLISH"), 409).path("code").asString())
                .isEqualTo("APPROVAL_REQUIRED");
        assertThat(send("POST", path, "MARKETING:luca", Map.of("action", "SUBMIT"), 200).path("status").asString())
                .isEqualTo("IN_REVIEW");
        JsonNode queued = find(send("GET", "/v1/approvals", "LEGAL:elena", null, 200), "IW-IT-GOV");
        assertThat(queued.path("entityType").asString()).isEqualTo("CONTEST");
        assertThat(queued.path("submittedBy").asString()).isEqualTo("MARKETING:luca");
        assertThat(queued.path("requiredRole").asString()).isEqualTo("LEGAL");
        assertThat(queued.path("summary").asString()).contains("premi", "dal");

        assertThat(status("POST", path, "CARE:paolo", Map.of("action", "APPROVE"))).isEqualTo(403);
        assertThat(send("POST", path, "LEGAL:elena", Map.of("action", "REJECT"), 422).path("code").asString())
                .isEqualTo("REJECT_COMMENT_REQUIRED");
        assertThat(send("POST", path, "LEGAL:elena", Map.of("action", "REJECT", "comment", "Manca il regolamento"), 200)
                .path("status").asString()).isEqualTo("DRAFT");
        JsonNode mine = find(send("GET", "/v1/approvals?submittedBy=MARKETING:luca", "MARKETING:luca", null, 200), "IW-IT-GOV");
        assertThat(mine.path("decision").asString()).isEqualTo("REJECT");
        assertThat(mine.path("comment").asString()).isEqualTo("Manca il regolamento");

        send("POST", path, "MARKETING:luca", Map.of("action", "SUBMIT"), 200);
        assertThat(send("POST", path, "LEGAL:elena", Map.of("action", "APPROVE", "comment", "Regolamento conforme"), 200)
                .path("status").asString()).isEqualTo("APPROVED");
        assertThat(status("POST", path, "LEGAL:elena", Map.of("action", "PUBLISH"))).isEqualTo(403);
        assertThat(send("POST", path, "MARKETING:luca", Map.of("action", "PUBLISH"), 200).path("status").asString())
                .isEqualTo("LIVE");

        JsonNode history = send("GET", "/v1/contests/" + id + "/approval-history", "ANALYST:sara", null, 200);
        List<String> actions = new java.util.ArrayList<>();
        history.forEach(h -> actions.add(h.path("action").asString()));
        assertThat(actions).containsExactly("PUBLISH", "APPROVE", "SUBMIT", "REJECT", "SUBMIT");
        assertThat(history.get(1).path("actor").asString()).isEqualTo("LEGAL:elena");
        assertThat(history.get(1).path("comment").asString()).isEqualTo("Regolamento conforme");
        assertThat(history.get(3).path("comment").asString()).isEqualTo("Manca il regolamento");

        // Seed: IW-NATALE è in coda da ieri, inviato dal marketing (docs/10).
        assertThat(find(send("GET", "/v1/approvals", "LEGAL:elena", null, 200), "IW-NATALE").path("submittedBy").asString())
                .isEqualTo("MARKETING:luca.marketing");
    }

    private static JsonNode find(JsonNode items, String code) {
        for (JsonNode i : items) {
            if (code.equals(i.path("code").asString())) {
                return i;
            }
        }
        throw new AssertionError("non in coda: " + code + " in " + items);
    }

    @Test
    void approvalIsForLegalAndAdmin() {
        String id = create("IW-IT-APPR", null).path("id").asString();
        send("POST", "/v1/contests/" + id + "/transitions", "MARKETING:luca", Map.of("action", "SUBMIT"), 200);
        assertThat(status("POST", "/v1/contests/" + id + "/transitions", "MARKETING:luca", Map.of("action", "APPROVE"))).isEqualTo(403);
        assertThat(send("POST", "/v1/contests/" + id + "/transitions", "LEGAL:elena", Map.of("action", "APPROVE"), 200)
                .path("status").asString()).isEqualTo("APPROVED");
        assertThat(status("POST", "/v1/contests", "ANALYST:sara", Map.of("code", "IW-IT-NOPE"))).isEqualTo(403);
        assertThat(send("POST", "/v1/contests", "MARKETING:luca", Map.of("code", "IW-IT-APPR", "name", "Doppione"), 409)
                .path("code").asString()).isEqualTo("CODE_TAKEN");
        assertThat(send("POST", "/v1/contests", "MARKETING:luca", Map.of("code", "IW-IT-BAD", "name", "Senza periodo",
                "mechanic", "DICE"), 422).path("code").asString()).isEqualTo("CONTEST_INVALID");
    }

    @Test
    void winnersStatsAndDelivery() {
        String csv = RestClient.create("http://localhost:" + port).get().uri("/v1/contests/IW-AUTUNNO/winners.csv")
                .retrieve().body(String.class);
        assertThat(csv).startsWith("playId,memberId,nickname,prizeCode");
        JsonNode stats = send("GET", "/v1/contests/IW-AUTUNNO/stats", "ANALYST:sara", null, 200);
        assertThat(stats.path("prizesTotal").asInt()).isEqualTo(355);
        assertThat(stats.path("prizes").size()).isEqualTo(4);
        assertThat(status("POST", "/v1/plays/NOPE/delivery", "MARKETING:luca", Map.of("status", "DELIVERED"))).isEqualTo(403);
        assertThat(status("POST", "/v1/plays/NOPE/delivery", "CARE:paolo", Map.of("status", "DELIVERED"))).isEqualTo(404);
    }

    // ---------- helper ----------

    /** M7.6: versione letta dall'editor (409 se superata) e *Duplica* in bozza con premi pieni e istanti da generare. */
    @Test
    void staleVersionIs409AndDuplicateStartsAsDraftWithoutInstants() {
        JsonNode created = create("IW-IT-DUP", null);
        String id = created.path("id").asString();
        long v0 = created.path("version").asLong();
        send("PUT", "/v1/contests/" + id, "MARKETING:luca", Map.of("name", "Ruota rinominata", "version", v0), 200);
        assertThat(send("PUT", "/v1/contests/" + id, "MARKETING:giulia", Map.of("name", "Altro nome", "version", v0), 409)
                .path("code").asString()).isEqualTo("VERSION_CONFLICT");
        send("POST", "/v1/contests/" + id + "/instants/generate", "MARKETING:luca", null, 200);

        assertThat(status("POST", "/v1/contests/" + id + "/duplicate", "ANALYST:sara", null)).isEqualTo(403);
        JsonNode copy = send("POST", "/v1/contests/" + id + "/duplicate", "MARKETING:luca", null, 201);
        assertThat(copy.path("code").asString()).isEqualTo("IW-IT-DUP-COPY-1");
        assertThat(copy.path("status").asString()).isEqualTo("DRAFT");
        assertThat(copy.path("name").asString()).isEqualTo("Ruota rinominata (copia)");
        assertThat(copy.path("instantsGeneratedAt").isNull() || copy.path("instantsGeneratedAt").isMissingNode()).isTrue();
        assertThat(copy.path("prizes")).hasSize(2);
        assertThat(copy.path("seed").asLong()).isNotEqualTo(send("GET", "/v1/contests/" + id, "ANALYST:sara", null, 200)
                .path("seed").asLong());
        assertThat(send("POST", "/v1/contests/" + id + "/duplicate", "MARKETING:luca", null, 201).path("code").asString())
                .isEqualTo("IW-IT-DUP-COPY-2");
    }

    private JsonNode create(String code, Long seed) {
        Instant start = Instant.now().minus(Duration.ofDays(1));
        Map<String, Object> body = new HashMap<>(Map.of("code", code, "name", "Ruota di prova", "mechanic", "WHEEL",
                "startAt", start.toString(), "endAt", start.plus(Duration.ofDays(10)).toString(), "freePlayDaily", true,
                "distribution", "UNIFORM", "prizes", List.of(prize("PTS-10", "POINTS", 10, null, 10),
                        prize("CAF", "COUPON", null, "RWD-COFFEE-5", 3))));
        if (seed != null) {
            body.put("seed", seed);
        }
        return send("POST", "/v1/contests", "MARKETING:luca", body, 201);
    }

    private static Map<String, Object> prize(String code, String type, Integer points, String rewardCode, int qty) {
        Map<String, Object> p = new HashMap<>(Map.of("code", code, "name", code, "type", type, "quantity", qty));
        if (points != null) p.put("points", points);
        if (rewardCode != null) p.put("rewardCode", rewardCode);
        return p;
    }

    private JsonNode contest(String code) {
        for (JsonNode c : get("/v1/contests")) {
            if (code.equals(c.path("code").asString())) {
                return c;
            }
        }
        throw new AssertionError("concorso assente: " + code);
    }

    private List<JsonNode> allInstants(String id) {
        List<JsonNode> out = new ArrayList<>();
        for (int page = 0; ; page++) {
            JsonNode p = send("GET", "/v1/contests/" + id + "/instants?size=100&page=" + page, "ADMIN:test", null, 200);
            p.path("items").forEach(out::add);
            if (page + 1 >= p.path("page").path("totalPages").asInt()) {
                return out;
            }
        }
    }

    private static List<String> signature(List<JsonNode> instants) {
        return instants.stream().map(i -> i.path("prizeCode").asString() + "@" + i.path("instantAt").asString()).toList();
    }

    private List<String> factTypesFor(String subject) {
        List<String> types = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "group.id", "contest-it-" + UUID.randomUUID(), "auto.offset.reset", "earliest",
                "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class))) {
            consumer.subscribe(List.of("lh.facts.v1"));
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline && types.isEmpty()) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    JsonNode e = mapper.readTree(r.value());
                    if (subject.equals(e.path("subject").asString())) {
                        types.add(e.path("type").asString());
                    }
                }
            }
        }
        return types;
    }

    private JsonNode get(String path) {
        return RestClient.create("http://localhost:" + port).get().uri(path).retrieve().body(JsonNode.class);
    }

    private int status(String method, String path, String actor, Object body) {
        var spec = RestClient.create("http://localhost:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (actor != null) spec = spec.header("X-LH-Actor", actor);
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
