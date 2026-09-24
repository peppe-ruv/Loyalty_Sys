package io.loyaltyhub.engagement;

import io.loyaltyhub.common.event.JsonSchemaValidator;
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
import org.springframework.core.io.ClassPathResource;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6.0 engagement (docs/servizi/engagement-service.md §3–§7, docs/03 §9; F-MSG-01, F-MSG-02, EVT-EFF-05, EVT-FACT-60):
 * inbox seminata coerente con le storie, {@code wallet.points.earned} di 162 PTS → "Hai guadagnato 162 punti" senza
 * duplicati anche se rielaborato, effetto {@code message.send} → inbox + {@code message.delivered} valido per lo schema,
 * {@code message.delivered} non produce nulla, regola con condizione ({@code referral.completed} solo REFERRER), non letti
 * e letture, ruoli sulla gestione, anteprima. EmbeddedKafka + Zonky.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EngagementIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String DELIVERED = "io.loyaltyhub.fact.message.delivered";

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonSchemaValidator validator;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=engagement");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    void seededInboxTellsTheStories() {
        assertThat(unread("MBR-000002")).as("Marco: 3 non letti").isEqualTo(3);
        assertThat(unread("MBR-000007")).as("Chiara: la scadenza").isEqualTo(1);
        assertThat(unread("MBR-000011")).as("Sofia: la richiesta confermata").isEqualTo(1);
        assertThat(unread("MBR-000001")).isZero();

        JsonNode marco = get("/v1/portal/inbox?memberId=MBR-000002");
        assertThat(marco.path("page").path("totalItems").asLong()).isEqualTo(6);
        assertThat(marco.path("items").get(0).path("title").asString()).isEqualTo("Hai guadagnato 88 punti");
        assertThat(marco.path("items").get(0).path("body").asString()).contains("Marco");
        assertThat(marco.path("items").get(0).path("read").asBoolean()).isFalse();

        JsonNode chiara = get("/v1/portal/inbox?memberId=MBR-000007&size=1");
        assertThat(chiara.path("page").path("totalPages").asInt()).isEqualTo(5);
        JsonNode expiring = find(get("/v1/portal/inbox?memberId=MBR-000007").path("items"), m -> !m.path("read").asBoolean());
        assertThat(expiring.path("title").asString()).isEqualTo("1.900 punti in scadenza");
        assertThat(expiring.path("category").asString()).isEqualTo("POINTS");
        assertThat(expiring.path("body").asString()).startsWith("Scadono il ").doesNotContain("{{");

        JsonNode sofia = find(get("/v1/portal/inbox?memberId=MBR-000011").path("items"), m -> !m.path("read").asBoolean());
        assertThat(sofia.path("title").asString()).isEqualTo("Richiesta confermata: Borraccia termica Aurora");

        JsonNode badges = get("/v1/messages?memberId=MBR-000005&category=GAME");
        assertThat(badges.path("page").path("totalItems").asLong()).as("i 5 badge di Francesca").isEqualTo(5);
        // Gli altri test possono aggiungere template/regole *-IT-*: si contano solo quelli del seed.
        assertThat(codes(get("/v1/message-templates")).stream().filter(c -> !c.contains("-IT-")).count()).isEqualTo(13);
        assertThat(codes(get("/v1/notification-rules")).stream().filter(c -> !c.contains("-IT-")).count()).isEqualTo(12);
        assertThat(codes(get("/v1/message-templates?category=TIER"))).containsExactlyInAnyOrder("MSG-TIER-UP", "MSG-TIER-DOWN");
        assertThat(get("/v1/notification-rules/NR-REFERRAL-DONE").path("condition").path("value").asString()).isEqualTo("REFERRER");
    }

    @Test
    void pointsEarnedBecomesOneMessageEvenWhenReprocessed() throws Exception {
        String id = "EVT-EARN-" + UUID.randomUUID();
        Map<String, Object> pts = earned(id, "MBR-000003", "PTS", 162);
        publish("lh.facts.v1", "MBR-000003", pts);
        // Lo stesso acquisto produce anche l'accredito STS: nessun messaggio (condizione data.currency = PTS).
        publish("lh.facts.v1", "MBR-000003", earned("EVT-EARN-STS-" + UUID.randomUUID(), "MBR-000003", "STS", 130));
        JsonNode message = awaitMessage("MBR-000003", m -> id.equals(m.path("sourceEventId").asString()));
        assertThat(message.path("title").asString()).isEqualTo("Hai guadagnato 162 punti");
        assertThat(message.path("body").asString()).contains("Giulia");
        assertThat(message.path("templateCode").asString()).isEqualTo("MSG-POINTS-EARNED");
        assertThat(message.path("sourceType").asString()).isEqualTo("wallet.points.earned");

        publish("lh.facts.v1", "MBR-000003", pts); // doppio invio: processed_event
        jdbc.sql("DELETE FROM processed_event WHERE consumer = 'lh-engagement' AND event_id = ?").param(id).update();
        publish("lh.facts.v1", "MBR-000003", pts); // rielaborazione vera: vince la terna unica
        awaitProcessed(id);
        List<JsonNode> delivered = factsFor("member:MBR-000003", DELIVERED, e -> id.equals(e.path("lhcausationid").asString()));
        assertThat(delivered).as("un solo message.delivered per l'evento").hasSize(1);
        assertThat(count("SELECT count(*) FROM inbox_message WHERE source_event_id = ?", id)).isEqualTo(1);
        assertThat(get("/v1/messages?memberId=MBR-000003&templateCode=MSG-POINTS-EARNED").path("items").toString())
                .doesNotContain("Hai guadagnato 130 punti");

        JsonNode fact = delivered.get(0);
        assertThat(fact.path("data").path("inboxMessageId").asString()).isEqualTo(message.path("id").asString());
        assertThat(fact.path("lhcorrelationid").asString()).isEqualTo("CORR-" + id);
        assertValid(fact);
    }

    @Test
    void messageSendEffectDeliversAndEmitsDelivered() throws Exception {
        String id = "EVT-SEND-" + UUID.randomUUID();
        Map<String, Object> effect = Map.of("specversion", "1.0", "id", id, "source", "urn:loyaltyhub:service:campaign",
                "type", "io.loyaltyhub.effect.message.send", "subject", "member:MBR-000004",
                "time", Instant.now().toString(), "lhcorrelationid", "CORR-" + id, "lhhop", 1,
                "data", Map.of("effectId", "EFF-" + id, "campaignCode", "CMP-BIRTHDAY", "actionId", "ACT-" + id,
                        "actionType", "member.birthday", "templateCode", "MSG-BIRTHDAY", "params", Map.of("age", 47)));
        publish("lh.effects.v1", "MBR-000004", effect);
        JsonNode message = awaitMessage("MBR-000004", m -> "MSG-BIRTHDAY".equals(m.path("templateCode").asString()));
        assertThat(message.path("title").asString()).isEqualTo("Buon compleanno, Davide!");
        assertThat(message.path("sourceEventId").asString()).as("deduplica sull'effectId").isEqualTo("EFF-" + id);
        assertThat(message.path("channel").asString()).isEqualTo("INAPP");

        List<JsonNode> delivered = factsFor("member:MBR-000004", DELIVERED, e -> id.equals(e.path("lhcausationid").asString()));
        assertThat(delivered).hasSize(1);
        assertThat(delivered.get(0).path("data").path("templateCode").asString()).isEqualTo("MSG-BIRTHDAY");
        assertThat(delivered.get(0).path("lhcorrelationid").asString()).isEqualTo("CORR-" + id);
        assertThat(delivered.get(0).path("lhhop").asInt()).isEqualTo(1);
        assertValid(delivered.get(0));
    }

    @Test
    void messageDeliveredNeverTriggersAnything() throws Exception {
        long before = count("SELECT count(*) FROM inbox_message WHERE member_id = ?", "MBR-000005");
        String loopId = "EVT-LOOP-" + UUID.randomUUID();
        publish("lh.facts.v1", "MBR-000005", Map.of("specversion", "1.0", "id", loopId,
                "source", "urn:loyaltyhub:service:engagement", "type", DELIVERED, "subject", "member:MBR-000005",
                "time", Instant.now().toString(), "lhcorrelationid", loopId, "lhhop", 0,
                "data", Map.of("templateCode", "MSG-BADGE", "channel", "INAPP", "inboxMessageId", "X")));
        // Sentinella sulla stessa partizione: quando il suo messaggio c'è, il fatto precedente è già passato.
        String sentinel = "EVT-SENTINEL-" + UUID.randomUUID();
        publish("lh.facts.v1", "MBR-000005", earned(sentinel, "MBR-000005", "PTS", 10));
        awaitMessage("MBR-000005", m -> sentinel.equals(m.path("sourceEventId").asString()));
        assertThat(count("SELECT count(*) FROM inbox_message WHERE member_id = ?", "MBR-000005")).isEqualTo(before + 1);
        assertThat(count("SELECT count(*) FROM processed_event WHERE event_id = ?", loopId)).as("ignorato prima del router").isZero();
        assertThat(factsFor("member:MBR-000005", DELIVERED, e -> loopId.equals(e.path("lhcausationid").asString()))).isEmpty();

        JsonNode problem = send("POST", "/v1/notification-rules", "ADMIN:marta.admin",
                Map.of("factType", "message.delivered", "templateCode", "MSG-BADGE"), 422);
        assertThat(problem.path("code").asString()).isEqualTo("RULE_INVALID");
    }

    @Test
    void referralRuleMatchesOnlyTheReferrer() throws Exception {
        String referee = "EVT-REF-EE-" + UUID.randomUUID();
        String referrer = "EVT-REF-ER-" + UUID.randomUUID();
        publish("lh.facts.v1", "MBR-000010", referral(referee, "MBR-000010", "REFEREE", "MBR-000006"));
        publish("lh.facts.v1", "MBR-000006", referral(referrer, "MBR-000006", "REFERRER", "MBR-000010"));
        JsonNode message = awaitMessage("MBR-000006", m -> referrer.equals(m.path("sourceEventId").asString()));
        assertThat(message.path("title").asString()).isEqualTo("Il tuo invito ha funzionato");
        assertThat(count("SELECT count(*) FROM processed_event WHERE event_id = ?", referee)).as("elaborato").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM inbox_message WHERE source_event_id = ?", referee)).as("REFEREE: nessun messaggio").isZero();
    }

    @Test
    void unreadCountReadAndReadAll() throws Exception {
        String member = "MBR-000009";
        assertThat(unread(member)).isZero();
        String first = "EVT-READ-1-" + UUID.randomUUID();
        publish("lh.facts.v1", member, earned(first, member, "PTS", 200));
        JsonNode m = awaitMessage(member, x -> first.equals(x.path("sourceEventId").asString()));
        assertThat(unread(member)).isEqualTo(1);

        assertThat(status("POST", "/v1/portal/inbox/" + m.path("id").asString() + "/read?memberId=MBR-000002", null))
                .as("messaggio di un altro membro").isEqualTo(404);
        JsonNode read = send("POST", "/v1/portal/inbox/" + m.path("id").asString() + "/read", null, Map.of("memberId", member), 200);
        assertThat(read.path("read").asBoolean()).isTrue();
        assertThat(read.path("readAt").asString()).isNotBlank();
        assertThat(unread(member)).isZero();

        String second = "EVT-READ-2-" + UUID.randomUUID();
        String third = "EVT-READ-3-" + UUID.randomUUID();
        publish("lh.facts.v1", member, earned(second, member, "PTS", 20));
        publish("lh.facts.v1", member, earned(third, member, "PTS", 30));
        awaitMessage(member, x -> third.equals(x.path("sourceEventId").asString()));
        assertThat(unread(member)).isEqualTo(2);
        JsonNode all = send("POST", "/v1/portal/inbox/read-all?memberId=" + member, null, null, 200);
        assertThat(all.path("marked").asInt()).isEqualTo(2);
        assertThat(all.path("unread").asLong()).isZero();
        assertThat(unread(member)).isZero();

        assertThat(send("GET", "/v1/portal/inbox/unread-count", null, null, 400).path("code").asString()).isEqualTo("BAD_REQUEST");
        assertThat(status("POST", "/v1/portal/inbox/read-all", Map.of())).isEqualTo(400);
    }

    @Test
    void managementRolesValidationAuditAndRuntimeRules() throws Exception {
        Map<String, Object> tpl = new HashMap<>(Map.of("code", "MSG-IT-COUPON-USED", "name", "Coupon usato",
                "channel", "INAPP", "category", "REWARD", "titleTpl", "Hai usato {{data.couponCode}}",
                "bodyTpl", "Grazie {{member.firstName}}!", "linkTarget", "/portal/my-rewards"));
        assertThat(send("POST", "/v1/message-templates", null, tpl, 403).path("code").asString()).isEqualTo("FORBIDDEN_ROLE");
        send("POST", "/v1/message-templates", "ANALYST:sara.analyst", tpl, 403);
        send("POST", "/v1/message-templates", "CARE:paolo.care", tpl, 403);
        send("POST", "/v1/message-templates", "LEGAL:elena.legal", tpl, 403);
        JsonNode created = send("POST", "/v1/message-templates", "MARKETING:luca.marketing", tpl, 201);
        assertThat(created.path("version").asLong()).isZero();
        assertThat(send("POST", "/v1/message-templates", "MARKETING:luca.marketing", tpl, 409).path("code").asString())
                .isEqualTo("CODE_TAKEN");

        Map<String, Object> bad = new HashMap<>(tpl);
        bad.put("code", "MSG-IT-BAD");
        bad.put("titleTpl", "{{amount|euro}}");
        bad.put("category", "NOPE");
        JsonNode invalid = send("POST", "/v1/message-templates", "ADMIN:marta.admin", bad, 422);
        assertThat(invalid.path("code").asString()).isEqualTo("TEMPLATE_INVALID");
        assertThat(invalid.path("errors").toString()).contains("category", "titleTpl");

        send("PUT", "/v1/message-templates/MSG-IT-COUPON-USED", "LEGAL:elena.legal", Map.of("name", "X"), 403);
        JsonNode updated = send("PUT", "/v1/message-templates/MSG-IT-COUPON-USED", "ADMIN:marta.admin",
                Map.of("titleTpl", "Hai usato il coupon {{data.couponCode}}", "version", 0), 200);
        assertThat(updated.path("version").asLong()).isEqualTo(1);
        assertThat(send("PUT", "/v1/message-templates/MSG-IT-COUPON-USED", "ADMIN:marta.admin",
                Map.of("name", "Vecchia", "version", 0), 409).path("code").asString()).isEqualTo("VERSION_CONFLICT");

        Map<String, Object> rule = Map.of("code", "NR-IT-COUPON-USED", "factType", "io.loyaltyhub.fact.coupon.used",
                "templateCode", "MSG-IT-COUPON-USED", "condition", Map.of("field", "data.couponCode", "cmp", "startsWith", "value", "CAF-"));
        send("POST", "/v1/notification-rules", "CARE:paolo.care", rule, 403);
        JsonNode r = send("POST", "/v1/notification-rules", "MARKETING:luca.marketing", rule, 201);
        assertThat(r.path("factType").asString()).isEqualTo("coupon.used");
        assertThat(send("POST", "/v1/notification-rules", "MARKETING:luca.marketing",
                Map.of("factType", "coupon.used", "templateCode", "MSG-IT-COUPON-USED",
                        "condition", Map.of("field", "member.tier", "cmp", "eq", "value", "GOLD")), 422).path("code").asString())
                .isEqualTo("RULE_INVALID");
        assertThat(send("POST", "/v1/notification-rules", "MARKETING:luca.marketing",
                Map.of("factType", "coupon.used", "templateCode", "MSG-NOPE"), 422).path("errors").toString()).contains("templateCode");

        long audits = count("SELECT count(*) FROM outbox WHERE type = 'io.loyaltyhub.audit.entry' AND msg_key IN (?, ?)",
                "MESSAGE_TEMPLATE:MSG-IT-COUPON-USED", "NOTIFICATION_RULE:NR-IT-COUPON-USED");
        assertThat(audits).as("create + update del template, create della regola").isEqualTo(3);

        // La regola nuova vale subito: coupon.used di Roberto (BLOCKED: i messaggi arrivano comunque).
        String used = "EVT-USED-" + UUID.randomUUID();
        publish("lh.facts.v1", "MBR-000008", Map.of("specversion", "1.0", "id", used, "source", "urn:loyaltyhub:service:reward",
                "type", "io.loyaltyhub.fact.coupon.used", "subject", "member:MBR-000008", "time", Instant.now().toString(),
                "lhcorrelationid", used, "lhhop", 0, "data", Map.of("couponCode", "CAF-7KQM-X3PD")));
        JsonNode m = awaitMessage("MBR-000008", x -> used.equals(x.path("sourceEventId").asString()));
        assertThat(m.path("title").asString()).isEqualTo("Hai usato il coupon CAF-7KQM-X3PD");
        assertThat(m.path("body").asString()).isEqualTo("Grazie Roberto!");

        // Disattivata: il fatto successivo non produce messaggi.
        send("PUT", "/v1/notification-rules/NR-IT-COUPON-USED", "ADMIN:marta.admin", Map.of("enabled", false), 200);
        String again = "EVT-USED-2-" + UUID.randomUUID();
        publish("lh.facts.v1", "MBR-000008", Map.of("specversion", "1.0", "id", again, "source", "urn:loyaltyhub:service:reward",
                "type", "io.loyaltyhub.fact.coupon.used", "subject", "member:MBR-000008", "time", Instant.now().toString(),
                "lhcorrelationid", again, "lhhop", 0, "data", Map.of("couponCode", "CAF-AAAA-BBBB")));
        awaitProcessed(again);
        assertThat(count("SELECT count(*) FROM inbox_message WHERE source_event_id = ?", again)).isZero();
    }

    @Test
    void renderPreviewResolvesPlaceholders() {
        JsonNode r = send("POST", "/v1/message-templates/MSG-POINTS-EARNED/render", null,
                Map.of("sampleEvent", Map.of("type", "io.loyaltyhub.fact.wallet.points.earned", "subject", "member:MBR-000002",
                        "data", Map.of("amount", 1500, "currency", "PTS"))), 200);
        assertThat(r.path("title").asString()).isEqualTo("Hai guadagnato 1.500 punti");
        assertThat(r.path("body").asString()).contains("Marco");
        assertThat(r.path("missing").size()).isZero();

        JsonNode draft = send("POST", "/v1/message-templates/MSG-POINTS-EARNED/render", "ANALYST:sara.analyst",
                Map.of("sampleEvent", Map.of("data", Map.of("amount", 7)), "titleTpl", "{{data.amount}} e {{data.nope}}"), 200);
        assertThat(draft.path("title").asString()).isEqualTo("7 e ");
        assertThat(draft.path("missing").toString()).contains("data.nope", "member.firstName");
        assertThat(status("POST", "/v1/message-templates/MSG-NOPE/render", Map.of())).isEqualTo(404);
    }

    // ---------- helper ----------

    private Map<String, Object> earned(String id, String memberId, String currency, long amount) {
        return Map.of("specversion", "1.0", "id", id, "source", "urn:loyaltyhub:service:wallet",
                "type", "io.loyaltyhub.fact.wallet.points.earned", "subject", "member:" + memberId,
                "time", Instant.now().toString(), "lhcorrelationid", "CORR-" + id, "lhcausationid", "EFF-" + id, "lhhop", 0,
                "data", Map.of("ledgerEntryId", "LED-" + id, "effectId", "EFF-" + id, "campaignCode", "CMP-PURCHASE-BASE",
                        "currency", currency, "baseAmount", amount, "amount", amount, "balanceAfter", 2000 + amount,
                        "pending", false));
    }

    private Map<String, Object> referral(String id, String memberId, String role, String counterpart) {
        return Map.of("specversion", "1.0", "id", id, "source", "urn:loyaltyhub:service:member",
                "type", "io.loyaltyhub.fact.referral.completed", "subject", "member:" + memberId,
                "time", Instant.now().toString(), "lhcorrelationid", "CORR-" + id, "lhhop", 0,
                "data", Map.of("role", role, "counterpartMemberId", counterpart, "qualifyingActionId", "ACT-" + id));
    }

    private void publish(String topic, String key, Map<String, Object> event) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class))) {
            producer.send(new ProducerRecord<>(topic, key, mapper.writeValueAsString(event))).get();
        }
    }

    private JsonNode awaitMessage(String memberId, Predicate<JsonNode> match) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            for (JsonNode m : get("/v1/messages?size=100&memberId=" + memberId).path("items")) {
                if (match.test(m)) {
                    return m;
                }
            }
            Thread.sleep(300);
        }
        throw new AssertionError("messaggio non arrivato per " + memberId);
    }

    private void awaitProcessed(String eventId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (count("SELECT count(*) FROM processed_event WHERE event_id = ?", eventId) > 0) {
                return;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("evento non elaborato: " + eventId);
    }

    private List<JsonNode> factsFor(String subject, String type, Predicate<JsonNode> match) {
        List<JsonNode> out = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "group.id", "engagement-it-" + UUID.randomUUID(), "auto.offset.reset", "earliest",
                "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class))) {
            consumer.subscribe(List.of("lh.facts.v1"));
            long deadline = System.currentTimeMillis() + 10_000;
            long quietUntil = 0;
            while (System.currentTimeMillis() < deadline && (out.isEmpty() || System.currentTimeMillis() < quietUntil)) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(400))) {
                    JsonNode e = mapper.readTree(r.value());
                    if (subject.equals(e.path("subject").asString()) && type.equals(e.path("type").asString()) && match.test(e)) {
                        out.add(e);
                        quietUntil = System.currentTimeMillis() + 3_000;
                    }
                }
            }
        }
        return out;
    }

    /** Il fatto realmente prodotto rispetta envelope e schema di contratto (docs/05 §9). */
    private void assertValid(JsonNode event) throws Exception {
        String envelope = new ClassPathResource("contracts/events/envelope.schema.json").getContentAsString(StandardCharsets.UTF_8);
        String data = new ClassPathResource("contracts/events/fact/message.delivered.schema.json").getContentAsString(StandardCharsets.UTF_8);
        assertThat(validator.validate("it-envelope", envelope, event.toString())).isEmpty();
        assertThat(validator.validate("it-message-delivered", data, event.path("data").toString())).isEmpty();
        assertThat(event.path("source").asString()).isEqualTo("urn:loyaltyhub:service:engagement");
        assertThat(event.path("dataschema").asString()).isEqualTo("urn:loyaltyhub:schema:fact.message.delivered:1");
    }

    private long unread(String memberId) {
        return get("/v1/portal/inbox/unread-count?memberId=" + memberId).path("unread").asLong();
    }

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    private static List<String> codes(JsonNode list) {
        List<String> out = new ArrayList<>();
        list.forEach(n -> out.add(n.path("code").asString()));
        return out;
    }

    private static JsonNode find(JsonNode items, Predicate<JsonNode> match) {
        for (JsonNode i : items) {
            if (match.test(i)) {
                return i;
            }
        }
        throw new AssertionError("nessun elemento corrispondente in " + items);
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
        var spec = RestClient.create("http://localhost:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (actor != null) spec = spec.header("X-LH-Actor", actor);
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
