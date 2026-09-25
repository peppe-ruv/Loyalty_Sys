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
import io.loyaltyhub.common.event.JsonSchemaValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * campaign-service M1.3 (docs/servizi/campaign-service.md §7): il motore end-to-end via Kafka. Col profilo
 * {@code demo} le 20 campagne e lo snapshot dei 12 membri sono caricati dai seed. Verifica: calcolo canonico
 * (feriale/weekend), limite per membro, effetto {@code message.send} (M6.4), duplicato, simulazione e portale.
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

    @Autowired
    private JsonSchemaValidator validator;

    @Autowired
    private io.loyaltyhub.campaign.application.CampaignAdminService admin;

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
    void birthdayGrantsPointsAndSendsMessageEffect() throws Exception {
        String id = "01BDAY01";
        publishAction(id, "member.birthday", "MBR-000004", TUESDAY, Map.of());

        try (KafkaConsumer<String, String> consumer = consumer("bday")) {
            consumer.subscribe(List.of(EFFECTS));
            ConsumerRecord<String, String> rec = poll(consumer, r -> {
                JsonNode e = readJson(r.value());
                return e.path("type").asString().equals("io.loyaltyhub.effect.message.send")
                        && e.path("data").path("actionId").asString().equals(id);
            });
            assertThat(rec).as("effetto message.send da CMP-BIRTHDAY").isNotNull();
            assertThat(rec.key()).isEqualTo("MBR-000004");
            JsonNode event = readJson(rec.value());
            assertThat(event.path("subject").asString()).isEqualTo("member:MBR-000004");
            assertThat(event.path("source").asString()).isEqualTo("urn:loyaltyhub:service:campaign");
            assertThat(event.path("dataschema").asString()).isEqualTo("urn:loyaltyhub:schema:effect.message.send:1");
            assertThat(event.path("lhcausationid").asString()).as("figlio dell'azione").isEqualTo(id);
            assertThat(event.path("lhcorrelationid").asString()).isEqualTo(id);
            JsonNode data = event.path("data");
            assertThat(data.path("templateCode").asString()).isEqualTo("MSG-BIRTHDAY");
            assertThat(data.path("campaignCode").asString()).isEqualTo("CMP-BIRTHDAY");
            assertThat(data.path("actionType").asString()).isEqualTo("member.birthday");
            assertThat(data.path("effectId").asString()).hasSize(26);
            assertThat(data.has("params")).as("nessun params nel seed").isFalse();

            String envelope = new ClassPathResource("contracts/events/envelope.schema.json").getContentAsString(StandardCharsets.UTF_8);
            String schema = new ClassPathResource("contracts/events/effect/message.send.schema.json").getContentAsString(StandardCharsets.UTF_8);
            assertThat(validator.validate("it-envelope", envelope, event.toString())).isEmpty();
            assertThat(validator.validate("it-message-send", schema, data.toString())).isEmpty();
        }
        try (KafkaConsumer<String, String> consumer = consumer("bday-pts")) {
            consumer.subscribe(List.of(EFFECTS));
            JsonNode pts = effect(consumer, id, "PTS");
            assertThat(pts.path("amount").asLong()).isEqualTo(250);
            assertThat(pts.path("campaignCode").asString()).isEqualTo("CMP-BIRTHDAY");
        }
        List<JsonNode> rows = pollEvaluations("MBR-000004", 1);
        assertThat(rows.get(0).path("outcome").asString()).isEqualTo("MATCHED");
        assertThat(rows.get(0).path("resultsJson").asString()).contains("SEND_MESSAGE").doesNotContain("EFFECT_NOT_SUPPORTED_YET");
    }

    @Test
    void validateRequiresTemplateCodeForSendMessage() {
        JsonNode res = client().post().uri("/v1/campaigns/validate").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("triggerActionTypes", List.of("member.birthday"),
                        "effects", List.of(Map.of("type", "SEND_MESSAGE"),
                                Map.of("type", "SEND_MESSAGE", "templateCode", "msg-lower"),
                                Map.of("type", "SEND_MESSAGE", "templateCode", "MSG-BIRTHDAY", "params", List.of(1)))))
                .retrieve().body(JsonNode.class);
        assertThat(res.path("valid").asBoolean()).isFalse();
        assertThat(res.path("errors").toString()).contains("SEND_MESSAGE.templateCode obbligatorio",
                "SEND_MESSAGE.templateCode non valido", "SEND_MESSAGE.params deve essere un oggetto");

        JsonNode ok = client().post().uri("/v1/campaigns/validate").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("triggerActionTypes", List.of("member.birthday"),
                        "effects", List.of(Map.of("type", "SEND_MESSAGE", "templateCode", "MSG-BIRTHDAY",
                                "params", Map.of("age", 39)))))
                .retrieve().body(JsonNode.class);
        assertThat(ok.path("valid").asBoolean()).isTrue();
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

    /**
     * {@code context.source} (docs/03 §3.3) è l'URN della fonte (docs/05 §2): la simulazione con il solo codice
     * ({@code ecommerce}, come la passa il backoffice) deve dare lo stesso esito della valutazione reale via Kafka.
     */
    @Test
    void contextSourceConditionBehavesTheSameInSimulationAndRealEvaluation() {
        Map<String, Object> body = Map.of(
                "code", "CMP-IT-SOURCE", "name", "Solo e-commerce", "triggerActionTypes", List.of("source.probe"),
                "conditions", Map.of("op", "all", "rules", List.of(
                        Map.of("field", "context.source", "cmp", "eq", "value", "urn:loyaltyhub:source:ecommerce"))),
                "effects", List.of(Map.of("type", "GRANT_POINTS", "currency", "PTS", "mode", "FIXED", "value", 5)),
                "schedule", Map.of("startAt", "2026-01-01T00:00:00Z"));
        String id = send("POST", "/v1/campaigns", "MARKETING:giulia", body, 201).path("id").asString();
        send("POST", "/v1/campaigns/" + id + "/transitions", "MARKETING:giulia", Map.of("action", "PUBLISH"), 200);

        // Valutazione reale: l'azione arriva da ingestion con source = urn:loyaltyhub:source:ecommerce.
        publishAction("01SRCPROBE01", "source.probe", "MBR-000009", TUESDAY, Map.of("probe", 1));
        String real = null;
        long deadline = System.currentTimeMillis() + 15_000;
        while (real == null && System.currentTimeMillis() < deadline) {
            real = client().get().uri("/v1/evaluations/01SRCPROBE01")
                    .exchange((req, res) -> res.getStatusCode().value() == 200 ? new String(res.getBody().readAllBytes()) : null);
            if (real == null) {
                sleep();
            }
        }
        assertThat(real).as("valutazione reale registrata").isNotNull();
        JsonNode realRow = mapper.readTree(real).get(0);
        assertThat(realRow.path("campaignCode").asString()).isEqualTo("CMP-IT-SOURCE");

        for (String source : List.of("ecommerce", "urn:loyaltyhub:source:ecommerce")) {
            JsonNode sim = send("POST", "/v1/campaigns/simulate", "MARKETING:giulia", Map.of(
                    "action", Map.of("type", "source.probe", "time", TUESDAY, "source", source, "data", Map.of("probe", 1)),
                    "memberId", "MBR-000009", "campaignIds", List.of(id)), 200);
            assertThat(sim.path("results").get(0).path("matched").asBoolean())
                    .as("simulazione con source=" + source).isEqualTo(realRow.path("matched").asBoolean()).isTrue();
        }
        JsonNode other = send("POST", "/v1/campaigns/simulate", "MARKETING:giulia", Map.of(
                "action", Map.of("type", "source.probe", "time", TUESDAY, "source", "app", "data", Map.of("probe", 1)),
                "memberId", "MBR-000009", "campaignIds", List.of(id)), 200);
        assertThat(other.path("results").get(0).path("reason").asString()).isEqualTo("CONDITION");
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

    /** PT-11: le due campagne referral per codice, anche quella non elencata in "Guadagna", coi valori reali. */
    @Test
    void portalByCodesIncludesHiddenReferralCampaigns() {
        JsonNode portal = client().get()
                .uri("/v1/portal/campaigns?memberId=MBR-000002&codes=CMP-REFERRAL-REFERRER,CMP-REFERRAL-REFEREE")
                .retrieve().body(JsonNode.class);
        assertThat(portal.size()).isEqualTo(2);
        for (JsonNode v : portal) {
            if (v.path("code").asString().equals("CMP-REFERRAL-REFERRER")) {
                assertThat(v.path("rewardSummary").asString()).isEqualTo("+500 punti · +250 punti status");
                assertThat(v.path("memberLimit").path("max").asInt()).isEqualTo(10);
                assertThat(v.path("memberLimit").path("period").asString()).isEqualTo("EDITION");
            } else {
                assertThat(v.path("rewardSummary").asString()).isEqualTo("+200 punti");
            }
        }
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
        // Creazione = object.edit (docs/08 §2): ANALYST, CARE, LEGAL e l'anonimo ricevono 403.
        int anonymous = client().post().uri("/v1/campaigns").contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange((req, res) -> res.getStatusCode().value());
        assertThat(anonymous).isEqualTo(403);
        assertThat(send("POST", "/v1/campaigns", "ANALYST:sara", body, 403).path("code").asString()).isEqualTo("FORBIDDEN_ROLE");
        send("POST", "/v1/campaigns", "CARE:paolo", body, 403);
        send("POST", "/v1/campaigns", "LEGAL:elena", body, 403);
        JsonNode created = send("POST", "/v1/campaigns", "MARKETING:giulia", body, 201);
        String id = created.path("id").asString();

        JsonNode draftEdit = put(id, Map.of("effects",
                List.of(Map.of("type", "GRANT_POINTS", "currency", "PTS", "mode", "FIXED", "value", 20))), 200);
        assertThat(draftEdit.path("effects").get(0).path("value").asInt()).isEqualTo(20);

        client().post().uri("/v1/campaigns/" + id + "/transitions").header("X-LH-Actor", "MARKETING:giulia")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("action", "PUBLISH")).retrieve().body(JsonNode.class);

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

    /** Fine automatica (campaign §5): LIVE/PAUSED con endAt superato → ENDED, con storico, fatto e cache aggiornata. */
    @Test
    void campaignsPastEndAtAreEndedByTheJob() {
        String endAt = "2020-01-31T23:00:00Z";
        Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "code", "CMP-IT-END-LIVE", "name", "Finita (live)", "triggerActionTypes", List.of("end.probe"),
                "effects", List.of(Map.of("type", "GRANT_POINTS", "currency", "PTS", "mode", "FIXED", "value", 1)),
                "schedule", Map.of("startAt", "2020-01-01T00:00:00Z", "endAt", endAt)));
        String live = send("POST", "/v1/campaigns", "MARKETING:giulia", body, 201).path("id").asString();
        send("POST", "/v1/campaigns/" + live + "/transitions", "MARKETING:giulia", Map.of("action", "PUBLISH"), 200);
        body.put("code", "CMP-IT-END-PAUSED");
        String paused = send("POST", "/v1/campaigns", "MARKETING:giulia", body, 201).path("id").asString();
        send("POST", "/v1/campaigns/" + paused + "/transitions", "MARKETING:giulia", Map.of("action", "PUBLISH"), 200);
        send("POST", "/v1/campaigns/" + paused + "/transitions", "MARKETING:giulia", Map.of("action", "PAUSE"), 200);
        body.put("code", "CMP-IT-END-DRAFT");
        String draft = send("POST", "/v1/campaigns", "MARKETING:giulia", body, 201).path("id").asString();

        // Esattamente a endAt la campagna è ancora nel calendario (confine incluso, come nel motore).
        assertThat(admin.endExpired(java.time.Instant.parse(endAt))).isZero();
        assertThat(send("GET", "/v1/campaigns/" + live, "ANALYST:sara", null, 200).path("status").asString()).isEqualTo("LIVE");

        try (KafkaConsumer<String, String> consumer = consumer("end-job")) {
            consumer.subscribe(List.of("lh.facts.v1"));
            assertThat(admin.endExpired(java.time.Instant.parse("2020-01-31T23:00:01Z"))).isEqualTo(2);
            ConsumerRecord<String, String> fact = poll(consumer, r -> {
                JsonNode e = readJson(r.value());
                return e.path("type").asString().equals("io.loyaltyhub.fact.campaign.status.changed")
                        && e.path("data").path("campaignCode").asString().equals("CMP-IT-END-PAUSED")
                        && e.path("data").path("newStatus").asString().equals("ENDED");
            });
            assertThat(fact).as("campaign.status.changed della fine automatica").isNotNull();
            JsonNode event = readJson(fact.value());
            assertThat(event.path("data").path("previousStatus").asString()).isEqualTo("PAUSED");
            assertThat(event.path("data").path("newStatus").asString()).isEqualTo("ENDED");
            assertThat(event.path("lhactor").asString()).isEqualTo("system");
        }
        assertThat(send("GET", "/v1/campaigns/" + live, "ANALYST:sara", null, 200).path("status").asString()).isEqualTo("ENDED");
        assertThat(send("GET", "/v1/campaigns/" + paused, "ANALYST:sara", null, 200).path("status").asString()).isEqualTo("ENDED");
        assertThat(send("GET", "/v1/campaigns/" + draft, "ANALYST:sara", null, 200).path("status").asString()).isEqualTo("DRAFT");
        JsonNode history = send("GET", "/v1/campaigns/" + live + "/approval-history", "ANALYST:sara", null, 200);
        assertThat(history.get(0).path("action").asString()).isEqualTo("END");
        assertThat(history.get(0).path("actor").asString()).isEqualTo("system");
        assertThat(admin.endExpired(java.time.Instant.parse("2020-01-31T23:00:01Z"))).as("idempotente").isZero();
    }

    /** M7.1 (docs/06 §7): campagna con budget oltre 100 000 punti → approvazione LEGAL; policy in sola lettura. */
    @Test
    void campaignAboveBudgetNeedsLegalApproval() {
        Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "code", "CMP-IT-BIG", "name", "Budget alto", "triggerActionTypes", List.of("review.submitted"),
                "effects", List.of(Map.of("type", "GRANT_POINTS", "currency", "PTS", "mode", "FIXED", "value", 10)),
                "limits", Map.of("global", Map.of("maxPoints", 500_000)),
                "schedule", Map.of("startAt", "2026-01-01T00:00:00Z")));
        String id = send("POST", "/v1/campaigns", "MARKETING:giulia", body, 201).path("id").asString();
        String path = "/v1/campaigns/" + id + "/transitions";
        assertThat(send("POST", path, "MARKETING:giulia", Map.of("action", "PUBLISH"), 409).path("code").asString())
                .isEqualTo("APPROVAL_REQUIRED");
        send("POST", path, "MARKETING:giulia", Map.of("action", "SUBMIT"), 200);
        JsonNode queue = send("GET", "/v1/approvals", "LEGAL:elena", null, 200);
        assertThat(queue.toString()).contains("CMP-IT-BIG", "budget oltre", "CMP-BLACK-FRIDAY");
        assertThat(send("POST", path, "ADMIN:marta", Map.of("action", "APPROVE", "comment", "Ok dal budget"), 200)
                .path("status").asString()).isEqualTo("APPROVED");
        assertThat(send("POST", path, "MARKETING:giulia", Map.of("action", "PUBLISH"), 200).path("status").asString())
                .isEqualTo("LIVE");
        JsonNode history = send("GET", "/v1/campaigns/" + id + "/approval-history", "ANALYST:sara", null, 200);
        assertThat(history.get(1).path("actor").asString()).isEqualTo("ADMIN:marta");
        JsonNode policy = send("GET", "/v1/approvals/policy", "ANALYST:sara", null, 200);
        assertThat(policy.path("enabled").asBoolean()).isTrue();
        assertThat(policy.path("campaignBudgetThreshold").asLong()).isEqualTo(100_000);
        assertThat(policy.path("rows").toString()).contains("CONTEST", "LEGAL");

        // Sotto soglia: pubblicazione diretta.
        Map<String, Object> small = new java.util.HashMap<>(body);
        small.put("code", "CMP-IT-SMALL");
        small.put("limits", Map.of("global", Map.of("maxPoints", 5_000)));
        String smallId = send("POST", "/v1/campaigns", "MARKETING:giulia", small, 201).path("id").asString();
        assertThat(send("POST", "/v1/campaigns/" + smallId + "/transitions", "MARKETING:giulia", Map.of("action", "PUBLISH"), 200)
                .path("status").asString()).isEqualTo("LIVE");
    }

    @Test
    void duplicateCopiesIntoDraftAndStaleVersionIs409() {
        Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "code", "CMP-IT-DUP", "name", "Da duplicare", "triggerActionTypes", List.of("review.submitted"),
                "effects", List.of(Map.of("type", "GRANT_POINTS", "currency", "PTS", "mode", "FIXED", "value", 7)),
                "schedule", Map.of("startAt", "2026-01-01T00:00:00Z")));
        JsonNode created = send("POST", "/v1/campaigns", "MARKETING:giulia", body, 201);
        String id = created.path("id").asString();
        long v0 = created.path("version").asLong();

        // Versioni (M7.6): chi salva con la versione letta vince, chi arriva dopo con quella vecchia riceve 409.
        JsonNode saved = send("PUT", "/v1/campaigns/" + id, "MARKETING:giulia", Map.of("name", "Prima", "version", v0), 200);
        assertThat(saved.path("version").asLong()).isEqualTo(v0 + 1);
        assertThat(send("PUT", "/v1/campaigns/" + id, "MARKETING:luca", Map.of("name", "Seconda", "version", v0), 409)
                .path("code").asString()).isEqualTo("VERSION_CONFLICT");
        assertThat(send("GET", "/v1/campaigns/" + id, "ANALYST:sara", null, 200).path("name").asString()).isEqualTo("Prima");

        // Duplica (F-CMP-13): DRAFT, codice -COPY-n progressivo, regole copiate; ANALYST non può.
        send("POST", "/v1/campaigns/" + id + "/duplicate", "ANALYST:sara", null, 403);
        JsonNode copy = send("POST", "/v1/campaigns/" + id + "/duplicate", "MARKETING:giulia", null, 201);
        assertThat(copy.path("code").asString()).isEqualTo("CMP-IT-DUP-COPY-1");
        assertThat(copy.path("status").asString()).isEqualTo("DRAFT");
        assertThat(copy.path("name").asString()).isEqualTo("Prima (copia)");
        assertThat(copy.path("effects").toString()).isEqualTo(saved.path("effects").toString());
        assertThat(copy.path("id").asString()).isNotEqualTo(id);
        assertThat(send("POST", "/v1/campaigns/" + id + "/duplicate", "MARKETING:giulia", null, 201).path("code").asString())
                .isEqualTo("CMP-IT-DUP-COPY-2");
    }

    private JsonNode send(String method, String path, String actor, Object body, int expected) {
        var spec = client().method(org.springframework.http.HttpMethod.valueOf(method)).uri(path).header("X-LH-Actor", actor);
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes());
            assertThat(res.getStatusCode().value()).as(method + " " + path + " → " + text).isEqualTo(expected);
            return text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text);
        });
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

    /**
     * M6.6 (F-CMP-06 P1, docs/servizi/campaign-service.md §4): il pubblico per segmento legge i segmenti dello snapshot,
     * alimentato dai fatti {@code member.segment.entered/left}. CMP-REVIEW ha pubblico {@code SEG-AT-RISK} (docs/10 §4).
     */
    @Test
    void segmentFactsDriveTheSegmentAudience() {
        String reviewId = null;
        for (JsonNode c : client().get().uri("/v1/campaigns").retrieve().body(JsonNode.class)) {
            if ("CMP-REVIEW".equals(c.path("code").asString())) {
                reviewId = c.path("id").asString();
            }
        }
        assertThat(reviewId).isNotNull();
        assertThat(reviewReason(reviewId, "MBR-000001")).isEqualTo("AUDIENCE");

        publishFact("io.loyaltyhub.fact.member.segment.entered", "MBR-000001", Map.of("segmentCode", "SEG-AT-RISK"));
        assertThat(awaitReason(reviewId, "MBR-000001", r -> !"AUDIENCE".equals(r))).isNotEqualTo("AUDIENCE");

        publishFact("io.loyaltyhub.fact.member.segment.left", "MBR-000001", Map.of("segmentCode", "SEG-AT-RISK"));
        assertThat(awaitReason(reviewId, "MBR-000001", "AUDIENCE"::equals)).isEqualTo("AUDIENCE");
    }

    private String awaitReason(String campaignId, String memberId, Predicate<String> done) {
        long deadline = System.currentTimeMillis() + 15_000;
        String reason = reviewReason(campaignId, memberId);
        while (!done.test(reason) && System.currentTimeMillis() < deadline) {
            sleep();
            reason = reviewReason(campaignId, memberId);
        }
        return reason;
    }

    /** Motivo di scarto di una campagna in simulazione ({@code MATCHED} se scatta). */
    private String reviewReason(String campaignId, String memberId) {
        Map<String, Object> body = Map.of(
                "action", Map.of("type", "review.submitted", "time", TUESDAY, "data", Map.of("productId", "SKU-1", "rating", 5)),
                "memberId", memberId, "campaignIds", List.of(campaignId));
        JsonNode res = client().post().uri("/v1/campaigns/simulate")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
        JsonNode r = res.path("results").get(0);
        return r.path("matched").asBoolean() ? "MATCHED" : r.path("reason").asString();
    }

    private void publishFact(String type, String memberId, Map<String, Object> data) {
        String id = "fact-" + System.nanoTime();
        Map<String, Object> event = Map.of(
                "specversion", "1.0", "id", id, "source", "urn:loyaltyhub:service:member",
                "type", type, "subject", "member:" + memberId,
                "time", java.time.Instant.now().toString(), "lhcorrelationid", id, "lhhop", 0, "data", data);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class))) {
            producer.send(new ProducerRecord<>("lh.facts.v1", memberId, mapper.writeValueAsString(event))).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
