package io.loyaltyhub.campaign;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * campaign-service M1.3 (docs/servizi/campaign-service.md §7): il motore end-to-end via Kafka. Col profilo
 * {@code demo} le 20 campagne e lo snapshot dei 12 membri sono caricati dai seed. Verifica: calcolo canonico
 * (feriale/weekend), limite per membro, effetto non supportato, duplicato, simulazione e portale.
 * Senza Docker: EmbeddedKafka + Zonky.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CampaignServiceIT {

    private static final String EFFECTS = "lh.effects.v1";
    private static final EmbeddedPostgres PG = startPg();

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    // Martedì e sabato di settembre 2026 (Europe/Rome).
    private static final String TUESDAY = "2026-09-15T09:00:00Z";
    private static final String SATURDAY = "2026-09-19T09:00:00Z";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=campaign");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    void purchaseWeekdaySilverGrantsBaseAndSts() {
        String id = "01ACTWD01";
        publishAction(id, "purchase.completed", "MBR-000003", TUESDAY,
                Map.of("orderId", "ORD-1", "amount", 130, "currency", "EUR"));

        try (KafkaConsumer<String, String> consumer = consumer("wd")) {
            consumer.subscribe(List.of(EFFECTS));
            JsonNode pts = effect(consumer, id, "PTS");
            assertThat(pts.path("baseAmount").asLong()).isEqualTo(130);
            assertThat(pts.path("campaignMultiplier").asDouble()).isEqualTo(1.0);
            assertThat(pts.path("amount").asLong()).isEqualTo(130);
            assertThat(pts.path("tierMultiplierApplies").asBoolean()).isTrue();
        }
        try (KafkaConsumer<String, String> consumer = consumer("wd2")) {
            consumer.subscribe(List.of(EFFECTS));
            assertThat(effect(consumer, id, "STS").path("amount").asLong()).isEqualTo(130);
        }
    }

    @Test
    void purchaseWeekendAppliesCampaignMultiplierToPts() {
        String id = "01ACTWE01";
        publishAction(id, "purchase.completed", "MBR-000007", SATURDAY,
                Map.of("orderId", "ORD-2", "amount", 130, "currency", "EUR"));

        try (KafkaConsumer<String, String> consumer = consumer("we")) {
            consumer.subscribe(List.of(EFFECTS));
            JsonNode pts = effect(consumer, id, "PTS");
            assertThat(pts.path("campaignMultiplier").asDouble()).isEqualTo(2.0);
            assertThat(pts.path("amount").asLong()).isEqualTo(260);
        }
    }

    @Test
    void appLoginDailyLimitSkipsSecondSameDay() {
        publishAction("01LOGIN01", "app.login.daily", "MBR-000010", TUESDAY, Map.of("platform", "IOS"));
        publishAction("01LOGIN02", "app.login.daily", "MBR-000010", TUESDAY, Map.of("platform", "IOS"));

        List<JsonNode> rows = pollEvaluations("MBR-000010", 2);
        long limited = rows.stream()
                .filter(r -> r.path("outcome").asString().equals("NO_MATCH"))
                .filter(r -> r.path("resultsJson").asString().contains("LIMIT"))
                .count();
        assertThat(limited).as("il secondo accesso del giorno è LIMIT").isGreaterThanOrEqualTo(1);
    }

    @Test
    void unsupportedEffectIsLoggedNotSupportedYet() {
        publishAction("01BDAY01", "member.birthday", "MBR-000004", TUESDAY, Map.of());
        List<JsonNode> rows = pollEvaluations("MBR-000004", 1);
        assertThat(rows.get(0).path("resultsJson").asString()).contains("EFFECT_NOT_SUPPORTED_YET");
    }

    @Test
    void surveyGrantsPointsAndAPlay() {
        publishAction("01SURVEY01", "survey.completed", "MBR-000003", TUESDAY, Map.of("surveyId", "SRV-1"));
        try (KafkaConsumer<String, String> consumer = consumer("plays")) {
            consumer.subscribe(List.of(EFFECTS));
            JsonNode plays = null;
            long deadline = System.currentTimeMillis() + 15_000;
            while (plays == null && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(400))) {
                    JsonNode e = readJson(r.value());
                    if (r.key().equals("MBR-000003") && e.path("type").asString().equals("io.loyaltyhub.effect.plays.grant")) {
                        plays = e.path("data");
                    }
                }
            }
            assertThat(plays).as("effetto plays.grant da CMP-SURVEY").isNotNull();
            assertThat(plays.path("contestCode").asString()).isEqualTo("IW-AUTUNNO");
            assertThat(plays.path("count").asInt()).isEqualTo(1);
            assertThat(plays.path("campaignCode").asString()).isEqualTo("CMP-SURVEY");
            assertThat(plays.path("effectId").asString()).hasSize(26);
        }
    }

    @Test
    void duplicateActionProducesEffectsOnce() {
        String id = "01DUP01";
        publishAction(id, "ebill.activated", "MBR-000002", TUESDAY, Map.of("contractId", "CTR-1"));
        publishAction(id, "ebill.activated", "MBR-000002", TUESDAY, Map.of("contractId", "CTR-1"));

        try (KafkaConsumer<String, String> consumer = consumer("dup")) {
            consumer.subscribe(List.of(EFFECTS));
            int count = 0;
            long deadline = System.currentTimeMillis() + 8_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(400))) {
                    JsonNode d = readJson(r.value()).path("data");
                    if (r.key().equals("MBR-000002") && d.path("currency").asString().equals("PTS")
                            && d.path("campaignCode").asString().equals("CMP-EBILL")) {
                        count++;
                    }
                }
            }
            assertThat(count).as("un solo accredito PTS malgrado il doppio invio").isEqualTo(1);
        }
    }

    @Test
    void simulateWeekendPurchaseWithoutWriting() {
        Map<String, Object> body = Map.of(
                "action", Map.of("type", "purchase.completed", "time", SATURDAY,
                        "data", Map.of("amount", 130, "currency", "EUR")),
                "memberId", "MBR-000005");
        JsonNode res = client().post().uri("/v1/campaigns/simulate")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);

        assertThat(res.path("outcome").asString()).isEqualTo("MATCHED");
        long pts = 0;
        for (JsonNode e : res.path("effects")) {
            if (e.path("currency").asString().equals("PTS")) {
                pts = e.path("amount").asLong();
            }
        }
        assertThat(pts).isEqualTo(260);
        // La simulazione non scrive: nessuna valutazione registrata per il membro.
        assertThat(client().get().uri("/v1/evaluations?memberId=MBR-000005").retrieve().body(JsonNode.class).size())
                .isZero();
    }

    @Test
    void simulateTierUpgradedWithLookupCampaign() {
        Map<String, Object> body = Map.of(
                "action", Map.of("type", "tier.upgraded", "time", TUESDAY,
                        "data", Map.of("newTier", "GOLD")),
                "memberId", "MBR-000003"); // active member

        JsonNode res = client().post().uri("/v1/campaigns/simulate")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);

        assertThat(res.path("outcome").asString()).isEqualTo("MATCHED");

        boolean foundPts = false;
        for (JsonNode e : res.path("effects")) {
            if (e.path("currency").asString().equals("PTS")) {
                assertThat(e.path("amount").asLong()).isEqualTo(500);
                assertThat(e.path("tierMultiplierApplies").asBoolean()).isFalse();
                assertThat(e.path("campaignCode").asString()).isEqualTo("CMP-TIER-UP-BONUS");
                foundPts = true;
            }
        }
        assertThat(foundPts).isTrue();
    }

    @Test
    void portalListsEarnRulesForMember() {
        JsonNode portal = client().get().uri("/v1/portal/campaigns?memberId=MBR-000003")
                .retrieve().body(JsonNode.class);
        boolean hasPurchase = false;
        for (JsonNode v : portal) {
            if (v.path("code").asString().equals("CMP-PURCHASE-BASE")) {
                hasPurchase = true;
                assertThat(v.path("rewardSummary").asString()).isNotBlank();
            }
        }
        assertThat(hasPurchase).isTrue();
    }

    @Test
    void listReturnsSeededCampaigns() {
        JsonNode all = client().get().uri("/v1/campaigns").retrieve().body(JsonNode.class);
        assertThat(all.size()).isGreaterThanOrEqualTo(20); // 20 seed (+ quelle create dai test)
    }

    @Test
    void statsReflectMatchesAndDailySeries() {
        // Un acquisto feriale attiva CMP-PURCHASE-BASE; le statistiche devono rifletterlo (F-CMP-10).
        publishAction("01STAT01", "purchase.completed", "MBR-000006", TUESDAY,
                Map.of("orderId", "ORD-ST", "amount", 100, "currency", "EUR"));
        pollEvaluations("MBR-000006", 1);

        String id = campaignIdByCode("CMP-PURCHASE-BASE");
        JsonNode stats = awaitStats(id, 1);
        assertThat(stats.path("matches").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(stats.path("uniqueMembers").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(stats.path("pointsDecided").asLong()).isGreaterThan(0);

        // Serie giornaliera 30 giorni non vuota, con almeno un giorno con attivazioni.
        JsonNode daily = stats.path("daily");
        assertThat(daily.size()).isGreaterThanOrEqualTo(1);
        long dayMatches = 0;
        for (JsonNode d : daily) {
            dayMatches += d.path("matches").asLong();
        }
        assertThat(dayMatches).isGreaterThanOrEqualTo(1);
    }

    @Test
    void liveCampaignAcceptsOnlySafeFieldsOtherwise409() {
        // Campagna propria (trigger non usato dagli altri test): DRAFT modificabile per intero, LIVE solo campi sicuri.
        Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "code", "CMP-IT-EDIT", "name", "Prova modifica", "triggerActionTypes", List.of("quiz.completed"),
                "effects", List.of(Map.of("type", "GRANT_POINTS", "currency", "PTS", "mode", "FIXED", "value", 10)),
                "schedule", Map.of("startAt", "2026-01-01T00:00:00Z")));
        JsonNode created = client().post().uri("/v1/campaigns").contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(JsonNode.class);
        String id = created.path("id").asString();

        JsonNode draftEdit = put(id, Map.of("effects",
                List.of(Map.of("type", "GRANT_POINTS", "currency", "PTS", "mode", "FIXED", "value", 20))), 200);
        assertThat(draftEdit.path("effects").get(0).path("value").asInt()).isEqualTo(20);

        client().post().uri("/v1/campaigns/" + id + "/transitions").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("action", "PUBLISH")).retrieve().body(JsonNode.class);

        JsonNode safe = put(id, Map.of("name", "Prova modifica (v2)", "priority", 150,
                "schedule", Map.of("startAt", "2026-01-01T00:00:00Z", "endAt", "2027-12-31T23:59:59Z")), 200);
        assertThat(safe.path("name").asString()).isEqualTo("Prova modifica (v2)");
        assertThat(safe.path("priority").asInt()).isEqualTo(150);
        assertThat(safe.path("status").asString()).isEqualTo("LIVE");

        JsonNode locked = put(id, Map.of("effects",
                List.of(Map.of("type", "GRANT_POINTS", "currency", "PTS", "mode", "FIXED", "value", 999))), 409);
        assertThat(locked.path("code").asString()).isEqualTo("CAMPAIGN_LIVE_LOCKED");
        assertThat(locked.path("detail").asString()).contains("effects");

        assertThat(put(id, Map.of("schedule", Map.of("startAt", "2026-02-01T00:00:00Z")), 409)
                .path("code").asString()).isEqualTo("CAMPAIGN_LIVE_LOCKED");
        assertThat(put(id, Map.of("code", "CMP-ALTRO"), 409).path("code").asString()).isEqualTo("CODE_IMMUTABLE");

        int analyst = client().put().uri("/v1/campaigns/" + id).header("X-LH-Actor", "ANALYST:luca")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("name", "x"))
                .exchange((req, res) -> res.getStatusCode().value());
        assertThat(analyst).isEqualTo(403);
    }

    private JsonNode put(String id, Map<String, Object> body, int expected) {
        return client().put().uri("/v1/campaigns/" + id).header("X-LH-Actor", "MARKETING:giulia")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange((req, res) -> {
                    assertThat(res.getStatusCode().value()).isEqualTo(expected);
                    return new ObjectMapper().readTree(res.getBody());
                });
    }

    // ---------- helper ----------

    private String campaignIdByCode(String code) {
        JsonNode all = client().get().uri("/v1/campaigns").retrieve().body(JsonNode.class);
        for (JsonNode c : all) {
            if (c.path("code").asString().equals(code)) {
                return c.path("id").asString();
            }
        }
        throw new AssertionError("Campagna non trovata per codice " + code);
    }

    private JsonNode awaitStats(String id, int minMatches) {
        long deadline = System.currentTimeMillis() + 15_000;
        JsonNode stats = null;
        while (System.currentTimeMillis() < deadline) {
            stats = client().get().uri("/v1/campaigns/" + id + "/stats").retrieve().body(JsonNode.class);
            if (stats != null && stats.path("matches").asLong() >= minMatches) {
                return stats;
            }
            sleep();
        }
        return stats;
    }

    private JsonNode effect(KafkaConsumer<String, String> consumer, String actionId, String currency) {
        ConsumerRecord<String, String> rec = poll(consumer, r -> {
            JsonNode d = readJson(r.value()).path("data");
            return d.path("actionId").asString().equals(actionId) && d.path("currency").asString().equals(currency);
        });
        assertThat(rec).as("effetto " + currency + " per " + actionId).isNotNull();
        return readJson(rec.value()).path("data");
    }

    private List<JsonNode> pollEvaluations(String memberId, int atLeast) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode rows = client().get().uri("/v1/evaluations?memberId=" + memberId)
                    .retrieve().body(JsonNode.class);
            if (rows.size() >= atLeast) {
                List<JsonNode> out = new java.util.ArrayList<>();
                rows.forEach(out::add);
                return out;
            }
            sleep();
        }
        throw new AssertionError("Attese " + atLeast + " valutazioni per " + memberId);
    }

    private void publishAction(String id, String shortType, String memberId, String time, Map<String, Object> data) {
        Map<String, Object> event = Map.of(
                "specversion", "1.0", "id", id, "source", "urn:loyaltyhub:source:ecommerce",
                "type", "io.loyaltyhub.action." + shortType, "subject", "member:" + memberId,
                "time", time, "lhcorrelationid", id, "lhhop", 0, "data", data);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class))) {
            producer.send(new ProducerRecord<>("lh.actions.v1", memberId, mapper.writeValueAsString(event))).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private KafkaConsumer<String, String> consumer(String group) {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, System.getProperty("spring.embedded.kafka.brokers"),
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
    }

    private ConsumerRecord<String, String> poll(KafkaConsumer<String, String> consumer,
                                                Predicate<ConsumerRecord<String, String>> match) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(400))) {
                if (match.test(r)) {
                    return r;
                }
            }
        }
        return null;
    }

    private JsonNode readJson(String value) {
        return mapper.readTree(value);
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
