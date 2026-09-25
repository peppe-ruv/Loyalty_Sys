package io.loyaltyhub.engagement;

import io.loyaltyhub.engagement.TestbookApi.Resp;
import io.loyaltyhub.engagement.application.ContentService;
import io.loyaltyhub.engagement.application.EngagementJobs;
import io.loyaltyhub.engagement.application.WebhookService;
import io.loyaltyhub.engagement.infra.InboxRepository;
import io.loyaltyhub.engagement.infra.PopupViewRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.aggregator.ArgumentsAccessor;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-ENG (docs/testbook/TB-ENG-engagement.md), messaggi: regole di notifica sui fatti da Kafka (RUL, tabella
 * completa tipo × condizione × attiva × stato del membro), gestione delle regole (RAD) e dei template (TAD), deduplica ed
 * effetti {@code message.send} (DDP), anteprima renderizzata (RND), inbox del portale (IBX), pulizie (CLN). Membri,
 * eventi, template e regole nuovi per ogni riga; tipi di fatto senza regole del seed. EmbeddedKafka + Zonky.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestbookEngMessagingIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String ADMIN = "ADMIN:marta.admin";
    private static final String FACT = "io.loyaltyhub.fact.";
    private static final String SEND = "io.loyaltyhub.effect.message.send";
    private static final String FACTS = "lh.facts.v1";
    private static final String EFFECTS = "lh.effects.v1";

    /** Regole della tabella RUL: una per combinazione condizione × attiva, ciascuna su un tipo di fatto senza regole del seed. */
    private static final Map<String, String> RUL_TYPES = Map.of(
            "NONE/ON", "coupon.used", "NONE/OFF", "wallet.points.refunded",
            "COND/ON", "wallet.points.spent", "COND/OFF", "wallet.points.adjusted");
    /** Tipo di fatto senza alcuna regola ("tipo diverso"). */
    private static final String NO_RULE_TYPE = "wallet.points.released";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private InboxRepository inboxRepository;

    @Autowired
    private PopupViewRepository popupViews;

    @Autowired
    private ContentService contents;

    @Autowired
    private WebhookService webhooks;

    private TestbookApi api;
    private final AtomicInteger seq = new AtomicInteger();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=engagement");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
        registry.add("loyaltyhub.webhooks.dispatcher.enabled", () -> "false");
    }

    @BeforeAll
    void setUp() {
        api = new TestbookApi(port, jdbc);
        template("MSG-TB-RUL", "INAPP", "Regola {{data.kind}}", "Ciao {{member.firstName}}");
        rule("NR-TB-NONE-ON", RUL_TYPES.get("NONE/ON"), null, true);
        rule("NR-TB-NONE-OFF", RUL_TYPES.get("NONE/OFF"), null, false);
        rule("NR-TB-COND-ON", RUL_TYPES.get("COND/ON"), Map.of("field", "data.kind", "cmp", "eq", "value", "X"), true);
        rule("NR-TB-COND-OFF", RUL_TYPES.get("COND/OFF"), Map.of("field", "data.kind", "cmp", "eq", "value", "X"), false);
    }

    @AfterAll
    void tearDown() throws Exception {
        api.close();
        PG.close();
    }

    // ================================================================= RUL

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/engagement/rul.csv", numLinesToSkip = 1, delimiter = '\t', quoteCharacter = '~',
            maxCharsPerColumn = 8192)
    void ruleMatching(ArgumentsAccessor row) throws Exception {
        // Q-180 DECISA: nessun messaggio ai membri INACTIVE (come gli ANONYMIZED, Q-70); il membro senza snapshot lo riceve
        // (scelta prudente: lo snapshot può arrivare dopo il fatto).
        String[] c = TestbookRows.columns(row);
        boolean typeMatch = "YES".equals(c[2]);
        String condition = c[3];
        String enabled = c[4];
        String status = c[5];
        String type = typeMatch ? RUL_TYPES.get(("NONE".equals(condition) ? "NONE" : "COND") + "/" + enabled) : NO_RULE_TYPE;
        String memberId = "UNKNOWN".equals(status) ? freshId() : member(status, "Giulia");
        String id = eventId();
        api.publish(FACTS, memberId, TestbookApi.event(id, FACT + type, memberId,
                Map.of("kind", "UNSAT".equals(condition) ? "Y" : "X")));
        api.awaitProcessed(id);
        assertThat(messages(memberId, id)).isEqualTo(Long.parseLong(c[6]));
    }

    // ================================================================= RAD

    @Test
    @DisplayName("[TB-ENG-RAD-001] regola valida: 201, attiva di default")
    void ruleCreate() {
        JsonNode r = api.ok("POST", "/v1/notification-rules", ADMIN,
                Map.of("code", code("NR"), "factType", "campaign.evaluated", "templateCode", "MSG-TB-RUL"), 201);
        assertThat(r.path("enabled").asBoolean()).isTrue();
        assertThat(r.path("factType").asString()).isEqualTo("campaign.evaluated");
    }

    @Test
    @DisplayName("[TB-ENG-RAD-002] tipo di fatto in forma completa: salvato in forma breve")
    void ruleFullType() {
        // TESTBOOK: ambiguo, vedi TB-ENG-RAD-002.
        JsonNode r = api.ok("POST", "/v1/notification-rules", ADMIN,
                Map.of("code", code("NR"), "factType", FACT + "wallet.points.expired", "templateCode", "MSG-TB-RUL", "enabled", false), 201);
        assertThat(r.path("factType").asString()).isEqualTo("wallet.points.expired");
    }

    @Test
    @DisplayName("[TB-ENG-RAD-003] tipo di fatto sconosciuto: 422 factType")
    void ruleUnknownType() {
        assertRuleInvalid(Map.of("factType", "wallet.points.inventati", "templateCode", "MSG-TB-RUL"), "factType");
    }

    @Test
    @DisplayName("[TB-ENG-RAD-004] tipo message.delivered: 422")
    void ruleMessageDelivered() {
        assertRuleInvalid(Map.of("factType", "message.delivered", "templateCode", "MSG-TB-RUL"), "factType");
    }

    @Test
    @DisplayName("[TB-ENG-RAD-005] tipo di fatto assente: 422 factType")
    void ruleNoType() {
        assertRuleInvalid(Map.of("templateCode", "MSG-TB-RUL"), "factType");
    }

    @Test
    @DisplayName("[TB-ENG-RAD-006] template inesistente: 422 templateCode")
    void ruleUnknownTemplate() {
        assertRuleInvalid(Map.of("factType", "edition.closed", "templateCode", "MSG-NON-ESISTE"), "templateCode");
    }

    @Test
    @DisplayName("[TB-ENG-RAD-007] condizione su member.tier: 422 condition")
    void ruleConditionOutsideData() {
        assertRuleInvalid(Map.of("factType", "edition.closed", "templateCode", "MSG-TB-RUL",
                "condition", Map.of("field", "member.tier", "cmp", "eq", "value", "GOLD")), "condition");
    }

    @Test
    @DisplayName("[TB-ENG-RAD-008] comparatore sconosciuto: 422 condition")
    void ruleUnknownComparator() {
        assertRuleInvalid(Map.of("factType", "edition.closed", "templateCode", "MSG-TB-RUL",
                "condition", Map.of("field", "data.amount", "cmp", "like", "value", 1)), "condition");
    }

    @Test
    @DisplayName("[TB-ENG-RAD-009] gruppo con operatore xor: 422 condition")
    void ruleUnknownGroup() {
        assertRuleInvalid(Map.of("factType", "edition.closed", "templateCode", "MSG-TB-RUL",
                "condition", Map.of("op", "xor", "rules", List.of())), "condition");
    }

    @Test
    @DisplayName("[TB-ENG-RAD-010] codice già usato: 409 CODE_TAKEN")
    void ruleDuplicate() {
        Map<String, Object> body = Map.of("code", code("NR"), "factType", "edition.closed", "templateCode", "MSG-TB-RUL", "enabled", false);
        api.ok("POST", "/v1/notification-rules", ADMIN, body, 201);
        Resp r = api.send("POST", "/v1/notification-rules", ADMIN, body);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("CODE_TAKEN");
    }

    @Test
    @DisplayName("[TB-ENG-RAD-011] disattivazione con versione superata: 409 VERSION_CONFLICT")
    void ruleVersionConflict() {
        String code = code("NR");
        api.ok("POST", "/v1/notification-rules", ADMIN,
                Map.of("code", code, "factType", "contest.plays.granted", "templateCode", "MSG-TB-RUL", "enabled", false), 201);
        api.ok("PUT", "/v1/notification-rules/" + code, ADMIN, Map.of("enabled", false, "version", 0), 200);
        Resp r = api.send("PUT", "/v1/notification-rules/" + code, ADMIN, Map.of("enabled", false, "version", 0));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("VERSION_CONFLICT");
    }

    @Test
    @DisplayName("[TB-ENG-RAD-012] modifica che cambia il codice: 409 CODE_IMMUTABLE")
    void ruleCodeImmutable() {
        // TESTBOOK: ambiguo, vedi TB-ENG-RAD-012.
        String code = code("NR");
        rule(code, "edition.closed", null, false);
        Resp r = api.send("PUT", "/v1/notification-rules/" + code, ADMIN, Map.of("code", code + "-ALTRO"));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("CODE_IMMUTABLE");
    }

    // ================================================================= DDP

    @Test
    @DisplayName("[TB-ENG-DDP-001] stesso fatto inviato due volte: 1 messaggio")
    void sameFactTwice() {
        String m = member("ACTIVE", "Anna");
        String id = eventId();
        Map<String, Object> e = TestbookApi.event(id, FACT + RUL_TYPES.get("NONE/ON"), m, Map.of("kind", "X"));
        api.publish(FACTS, m, e);
        api.publish(FACTS, m, e);
        sentinel(m);
        assertThat(messages(m, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("[TB-ENG-DDP-002] stesso fatto rielaborato: 1 messaggio (terna membro, evento, template)")
    void reprocessedFact() {
        String m = member("ACTIVE", "Anna");
        String id = eventId();
        Map<String, Object> e = TestbookApi.event(id, FACT + RUL_TYPES.get("NONE/ON"), m, Map.of("kind", "X"));
        api.publish(FACTS, m, e);
        api.awaitProcessed(id);
        jdbc.sql("DELETE FROM processed_event WHERE event_id = ?").param(id).update();
        api.publish(FACTS, m, e);
        api.awaitProcessed(id);
        assertThat(messages(m, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("[TB-ENG-DDP-003] due regole sullo stesso tipo con lo stesso template: 1 messaggio")
    void twoRulesSameTemplate() {
        String tpl = template(code("MSG"), "INAPP", "Stesso template", "Testo");
        rule(code("NR"), "contest.played", null, true, tpl);
        rule(code("NR"), "contest.played", null, true, tpl);
        String m = member("ACTIVE", "Anna");
        String id = eventId();
        api.publish(FACTS, m, TestbookApi.event(id, FACT + "contest.played", m, Map.of("contestCode", "IW-TB")));
        api.awaitProcessed(id);
        assertThat(messages(m, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("[TB-ENG-DDP-004] due regole sullo stesso tipo con template diversi: 2 messaggi")
    void twoRulesTwoTemplates() {
        rule(code("NR"), "achievement.progressed", null, true, template(code("MSG"), "INAPP", "Primo", "Testo"));
        rule(code("NR"), "achievement.progressed", null, true, template(code("MSG"), "INAPP", "Secondo", "Testo"));
        String m = member("ACTIVE", "Anna");
        String id = eventId();
        api.publish(FACTS, m, TestbookApi.event(id, FACT + "achievement.progressed", m, Map.of("achievementCode", "ACH-TB")));
        api.awaitProcessed(id);
        assertThat(messages(m, id)).isEqualTo(2);
    }

    @Test
    @DisplayName("[TB-ENG-DDP-005] messaggio nuovo e suo duplicato: un solo message.delivered figlio del fatto")
    void deliveredOncePerMessage() {
        String m = member("ACTIVE", "Anna");
        String id = eventId();
        Map<String, Object> e = TestbookApi.event(id, FACT + RUL_TYPES.get("NONE/ON"), m, Map.of("kind", "X"));
        api.publish(FACTS, m, e);
        api.awaitProcessed(id);
        jdbc.sql("DELETE FROM processed_event WHERE event_id = ?").param(id).update();
        api.publish(FACTS, m, e);
        api.awaitProcessed(id);
        List<JsonNode> delivered = outbox(FACT + "message.delivered", id);
        assertThat(delivered).hasSize(1);
        assertThat(delivered.getFirst().path("subject").asString()).isEqualTo("member:" + m);
        assertThat(delivered.getFirst().path("data").path("templateCode").asString()).isEqualTo("MSG-TB-RUL");
    }

    @Test
    @DisplayName("[TB-ENG-DDP-006] fatto message.delivered in ingresso: nessun messaggio")
    void deliveredFactIgnored() {
        String m = member("ACTIVE", "Anna");
        String id = eventId();
        api.publish(FACTS, m, TestbookApi.event(id, FACT + "message.delivered", m,
                Map.of("templateCode", "MSG-TB-RUL", "channel", "INAPP", "inboxMessageId", "X")));
        sentinel(m);
        assertThat(messages(m, id)).isZero();
        assertThat(outbox(FACT + "message.delivered", id)).isEmpty();
    }

    @Test
    @DisplayName("[TB-ENG-DDP-007] effetto message.send con params: messaggio col template e i parametri")
    void messageSendEffect() {
        String tpl = template(code("MSG"), "INAPP", "Ciao {{data.name}}", "Hai {{data.age}} anni ({{data.campaignCode}})");
        String m = member("ACTIVE", "Anna");
        String id = eventId();
        api.publish(EFFECTS, m, TestbookApi.event(id, SEND, m, Map.of("effectId", "EFF-" + id, "campaignCode", "CMP-TB",
                "templateCode", tpl, "params", Map.of("name", "Ada", "age", 47))));
        api.awaitProcessed(id);
        JsonNode msg = message(m, "EFF-" + id);
        assertThat(msg.path("title").asString()).isEqualTo("Ciao Ada");
        assertThat(msg.path("body").asString()).isEqualTo("Hai 47 anni (CMP-TB)");
    }

    @Test
    @DisplayName("[TB-ENG-DDP-008] due message.send con lo stesso effectId: 1 messaggio")
    void messageSendSameEffect() {
        String tpl = template(code("MSG"), "INAPP", "Effetto", "Testo");
        String m = member("ACTIVE", "Anna");
        String effectId = "EFF-" + eventId();
        String a = eventId();
        String b = eventId();
        api.publish(EFFECTS, m, TestbookApi.event(a, SEND, m, Map.of("effectId", effectId, "templateCode", tpl)));
        api.publish(EFFECTS, m, TestbookApi.event(b, SEND, m, Map.of("effectId", effectId, "templateCode", tpl)));
        api.awaitProcessed(a);
        api.awaitProcessed(b);
        assertThat(api.count("SELECT count(*) FROM inbox_message WHERE member_id = ?", m)).isEqualTo(1);
    }

    @Test
    @DisplayName("[TB-ENG-DDP-009] due message.send senza effectId: 2 messaggi")
    void messageSendNoEffectId() {
        String tpl = template(code("MSG"), "INAPP", "Effetto", "Testo");
        String m = member("ACTIVE", "Anna");
        String a = eventId();
        String b = eventId();
        api.publish(EFFECTS, m, TestbookApi.event(a, SEND, m, Map.of("templateCode", tpl)));
        api.publish(EFFECTS, m, TestbookApi.event(b, SEND, m, Map.of("templateCode", tpl)));
        api.awaitProcessed(a);
        api.awaitProcessed(b);
        assertThat(messages(m, a)).isEqualTo(1);
        assertThat(messages(m, b)).isEqualTo(1);
    }

    @Test
    @DisplayName("[TB-ENG-DDP-010] message.send con template inesistente: nessun messaggio, DLQ TEMPLATE_NOT_FOUND")
    void messageSendUnknownTemplate() {
        String tpl = template(code("MSG"), "INAPP", "Sentinella", "Testo");
        String m = member("ACTIVE", "Anna");
        String bad = eventId();
        api.publish(EFFECTS, m, TestbookApi.event(bad, SEND, m, Map.of("effectId", "EFF-" + bad, "templateCode", "MSG-NON-ESISTE")));
        String ok = eventId();
        api.publish(EFFECTS, m, TestbookApi.event(ok, SEND, m, Map.of("effectId", "EFF-" + ok, "templateCode", tpl)));
        api.awaitProcessed(ok);
        assertThat(messages(m, "EFF-" + bad)).isZero();
        assertThat(messages(m, "EFF-" + ok)).isEqualTo(1);
        assertThat(dlqErrorCode(bad)).isEqualTo("TEMPLATE_NOT_FOUND");
    }

    @Test
    @DisplayName("[TB-ENG-DDP-014] message.send senza membro: nessun messaggio, DLQ INVALID_EFFECT")
    void messageSendWithoutMember() {
        // TESTBOOK: ambiguo, vedi TB-ENG-DDP-014.
        String tpl = template(code("MSG"), "INAPP", "Effetto", "Testo");
        String bad = eventId();
        Map<String, Object> e = TestbookApi.event(bad, SEND, "X", Map.of("effectId", "EFF-" + bad, "templateCode", tpl));
        e.put("subject", "campaign:CMP-TB");
        api.publish(EFFECTS, "CMP-TB", e);
        assertThat(dlqErrorCode(bad)).isEqualTo("INVALID_EFFECT");
        assertThat(api.count("SELECT count(*) FROM inbox_message WHERE source_event_id = ?", "EFF-" + bad)).isZero();
    }

    @Test
    @DisplayName("[TB-ENG-DDP-011] message.send a un membro ANONYMIZED: nessun messaggio")
    void messageSendAnonymized() {
        String tpl = template(code("MSG"), "INAPP", "Effetto", "Testo");
        String m = member("ANONYMIZED", "Membro anonimo");
        String id = eventId();
        api.publish(EFFECTS, m, TestbookApi.event(id, SEND, m, Map.of("effectId", "EFF-" + id, "templateCode", tpl)));
        api.awaitProcessed(id);
        assertThat(api.count("SELECT count(*) FROM inbox_message WHERE member_id = ?", m)).isZero();
    }

    @Test
    @DisplayName("[TB-ENG-DDP-012] regola con template EMAIL_FAKE: solo nel registro /v1/messages")
    void emailFakeOnlyInLog() {
        String tpl = template(code("MSG"), "EMAIL_FAKE", "E-mail di prova", "Testo");
        rule(code("NR"), "reward.redemption.requested", null, true, tpl);
        String m = member("ACTIVE", "Anna");
        String id = eventId();
        api.publish(FACTS, m, TestbookApi.event(id, FACT + "reward.redemption.requested", m, Map.of("redemptionId", "RED-TB")));
        api.awaitProcessed(id);
        assertThat(api.get("/v1/messages?memberId=" + m + "&channel=EMAIL_FAKE").body().path("page").path("totalItems").asLong())
                .isEqualTo(1);
        assertThat(api.get("/v1/portal/inbox?memberId=" + m).body().path("page").path("totalItems").asLong()).isZero();
        assertThat(api.get("/v1/portal/inbox/unread-count?memberId=" + m).body().path("unread").asLong()).isZero();
    }

    @Test
    @DisplayName("[TB-ENG-DDP-013] messaggio reso: data, member.firstName, percorso assente vuoto")
    void renderedDelivery() {
        String tpl = template(code("MSG"), "INAPP", "Hai guadagnato {{data.amount|number}} punti", "Ciao {{member.firstName}}[{{data.missing}}]");
        rule(code("NR"), "wallet.spend.rejected", null, true, tpl);
        String m = member("ACTIVE", "Giulia");
        String id = eventId();
        api.publish(FACTS, m, TestbookApi.event(id, FACT + "wallet.spend.rejected", m, Map.of("amount", 1500)));
        api.awaitProcessed(id);
        JsonNode msg = message(m, id);
        assertThat(msg.path("title").asString()).isEqualTo("Hai guadagnato 1.500 punti");
        assertThat(msg.path("body").asString()).isEqualTo("Ciao Giulia[]");
    }

    // ================================================================= TAD

    @Test
    @DisplayName("[TB-ENG-TAD-001] template valido INAPP: 201, versione 0")
    void templateCreate() {
        JsonNode t = api.ok("POST", "/v1/message-templates", ADMIN, templateBody(code("MSG"), "INAPP", "PROGRAM"), 201);
        assertThat(t.path("version").asLong()).isZero();
        assertThat(t.path("channel").asString()).isEqualTo("INAPP");
    }

    @Test
    @DisplayName("[TB-ENG-TAD-002] canale EMAIL_FAKE: 201")
    void templateEmailFake() {
        assertThat(api.ok("POST", "/v1/message-templates", ADMIN, templateBody(code("MSG"), "EMAIL_FAKE", "PROGRAM"), 201)
                .path("channel").asString()).isEqualTo("EMAIL_FAKE");
    }

    @Test
    @DisplayName("[TB-ENG-TAD-003] canale sconosciuto SMS: 422 channel")
    void templateBadChannel() {
        assertTemplateInvalid(templateBody(code("MSG"), "SMS", "PROGRAM"), "channel");
    }

    @Test
    @DisplayName("[TB-ENG-TAD-004] canale assente: 201 INAPP")
    void templateDefaultChannel() {
        // TESTBOOK: ambiguo, vedi TB-ENG-TAD-004.
        Map<String, Object> body = templateBody(code("MSG"), "INAPP", "PROGRAM");
        body.remove("channel");
        assertThat(api.ok("POST", "/v1/message-templates", ADMIN, body, 201).path("channel").asString()).isEqualTo("INAPP");
    }

    @Test
    @DisplayName("[TB-ENG-TAD-005] le 5 categorie: 201 per ciascuna")
    void templateCategories() {
        for (String category : List.of("POINTS", "TIER", "REWARD", "GAME", "PROGRAM")) {
            assertThat(api.ok("POST", "/v1/message-templates", ADMIN, templateBody(code("MSG"), "INAPP", category), 201)
                    .path("category").asString()).isEqualTo(category);
        }
    }

    @Test
    @DisplayName("[TB-ENG-TAD-006] categoria sconosciuta ALTRO: 422 category")
    void templateBadCategory() {
        assertTemplateInvalid(templateBody(code("MSG"), "INAPP", "ALTRO"), "category");
    }

    @Test
    @DisplayName("[TB-ENG-TAD-007] titolo con {{amount}} senza radice: 422 titleTpl")
    void templateBadRoot() {
        Map<String, Object> body = templateBody(code("MSG"), "INAPP", "PROGRAM");
        body.put("titleTpl", "Hai {{amount}} punti");
        assertTemplateInvalid(body, "titleTpl");
    }

    @Test
    @DisplayName("[TB-ENG-TAD-008] testo con formattatore |euro: 422 bodyTpl")
    void templateBadFormatter() {
        Map<String, Object> body = templateBody(code("MSG"), "INAPP", "PROGRAM");
        body.put("bodyTpl", "Valore {{data.amount|euro}}");
        assertTemplateInvalid(body, "bodyTpl");
    }

    @Test
    @DisplayName("[TB-ENG-TAD-009] titolo assente: 422 titleTpl")
    void templateNoTitle() {
        Map<String, Object> body = templateBody(code("MSG"), "INAPP", "PROGRAM");
        body.remove("titleTpl");
        assertTemplateInvalid(body, "titleTpl");
    }

    @Test
    @DisplayName("[TB-ENG-TAD-010] nome assente: 422 name")
    void templateNoName() {
        // TESTBOOK: ambiguo, vedi TB-ENG-TAD-010.
        Map<String, Object> body = templateBody(code("MSG"), "INAPP", "PROGRAM");
        body.remove("name");
        assertTemplateInvalid(body, "name");
    }

    @Test
    @DisplayName("[TB-ENG-TAD-011] codice già usato: 409 CODE_TAKEN")
    void templateDuplicate() {
        Map<String, Object> body = templateBody(code("MSG"), "INAPP", "PROGRAM");
        api.ok("POST", "/v1/message-templates", ADMIN, body, 201);
        Resp r = api.send("POST", "/v1/message-templates", ADMIN, body);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("CODE_TAKEN");
    }

    @Test
    @DisplayName("[TB-ENG-TAD-012] modifica con versione superata: 409 VERSION_CONFLICT")
    void templateVersionConflict() {
        String code = code("MSG");
        api.ok("POST", "/v1/message-templates", ADMIN, templateBody(code, "INAPP", "PROGRAM"), 201);
        api.ok("PUT", "/v1/message-templates/" + code, ADMIN, Map.of("name", "Nuovo", "version", 0), 200);
        Resp r = api.send("PUT", "/v1/message-templates/" + code, ADMIN, Map.of("name", "Tardi", "version", 0));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("VERSION_CONFLICT");
    }

    @Test
    @DisplayName("[TB-ENG-TAD-013] modifica di un template inesistente: 404")
    void templateNotFound() {
        assertThat(api.send("PUT", "/v1/message-templates/MSG-NON-ESISTE", ADMIN, Map.of("name", "x")).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("[TB-ENG-TAD-014] modifica che cambia il codice: 409 CODE_IMMUTABLE")
    void templateCodeImmutable() {
        // TESTBOOK: ambiguo, vedi TB-ENG-TAD-014.
        String code = template(code("MSG"), "INAPP", "Titolo", "Testo");
        Resp r = api.send("PUT", "/v1/message-templates/" + code, ADMIN, Map.of("code", code + "-ALTRO"));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("CODE_IMMUTABLE");
    }

    // ================================================================= RND

    @Test
    @DisplayName("[TB-ENG-RND-001] anteprima su evento campione: segnaposto risolti")
    void renderSample() {
        String tpl = template(code("MSG"), "INAPP", "Hai guadagnato {{data.amount|number}} punti", "Ciao {{member.firstName}}");
        String m = member("ACTIVE", "Giulia");
        JsonNode r = api.ok("POST", "/v1/message-templates/" + tpl + "/render", ADMIN,
                Map.of("sampleEvent", Map.of("type", FACT + "wallet.points.earned", "subject", "member:" + m,
                        "data", Map.of("amount", 1500))), 200);
        assertThat(r.path("title").asString()).isEqualTo("Hai guadagnato 1.500 punti");
        assertThat(r.path("body").asString()).isEqualTo("Ciao Giulia");
        assertThat(r.path("missing").size()).isZero();
    }

    @Test
    @DisplayName("[TB-ENG-RND-002] anteprima di una bozza con percorso assente: mancanti elencati")
    void renderDraft() {
        String tpl = template(code("MSG"), "INAPP", "Titolo", "Testo");
        JsonNode r = api.ok("POST", "/v1/message-templates/" + tpl + "/render", ADMIN,
                Map.of("sampleEvent", Map.of("data", Map.of("amount", 7)), "titleTpl", "{{data.amount}} e {{data.nope}}"), 200);
        assertThat(r.path("title").asString()).isEqualTo("7 e ");
        assertThat(r.path("missing").toString()).contains("data.nope");
    }

    @Test
    @DisplayName("[TB-ENG-RND-003] anteprima di un template inesistente: 404")
    void renderNotFound() {
        assertThat(api.send("POST", "/v1/message-templates/MSG-NON-ESISTE/render", ADMIN, Map.of()).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("[TB-ENG-RND-004] anteprima con ruolo ANALYST: 200")
    void renderAnalyst() {
        String tpl = template(code("MSG"), "INAPP", "Titolo", "Testo");
        assertThat(api.send("POST", "/v1/message-templates/" + tpl + "/render", "ANALYST:sara.analyst",
                Map.of("sampleEvent", Map.of("data", Map.of()))).status()).isEqualTo(200);
    }

    // ================================================================= IBX

    @Test
    @DisplayName("[TB-ENG-IBX-001] non letti di un membro senza messaggi: 0")
    void unreadZero() {
        assertThat(unread(freshId())).isZero();
    }

    @Test
    @DisplayName("[TB-ENG-IBX-002] non letti: 3 INAPP non letti, 1 INAPP letto, 1 EMAIL_FAKE non letto → 3")
    void unreadCountsInAppOnly() {
        assertThat(unread(inboxFixture().memberId())).isEqualTo(3);
    }

    @Test
    @DisplayName("[TB-ENG-IBX-003] inbox del portale: solo INAPP, dal più recente")
    void inboxOrderAndChannel() {
        Fixture f = inboxFixture();
        JsonNode page = api.ok("GET", "/v1/portal/inbox?memberId=" + f.memberId(), null, null, 200);
        List<String> titles = new ArrayList<>();
        page.path("items").forEach(i -> titles.add(i.path("title").asString()));
        assertThat(titles).containsExactly("M4 letto", "M3", "M2", "M1");
    }

    @Test
    @DisplayName("[TB-ENG-IBX-004] segna letto: read=true, readAt, non letti −1")
    void markRead() {
        Fixture f = inboxFixture();
        JsonNode r = api.ok("POST", "/v1/portal/inbox/" + f.unreadIds().getFirst() + "/read?memberId=" + f.memberId(), null, null, 200);
        assertThat(r.path("read").asBoolean()).isTrue();
        assertThat(r.path("readAt").asString()).isNotBlank();
        assertThat(unread(f.memberId())).isEqualTo(2);
    }

    @Test
    @DisplayName("[TB-ENG-IBX-005] segna letto un messaggio già letto: 200, readAt invariato")
    void markReadTwice() {
        // TESTBOOK: ambiguo, vedi TB-ENG-IBX-005.
        Fixture f = inboxFixture();
        String path = "/v1/portal/inbox/" + f.unreadIds().getFirst() + "/read?memberId=" + f.memberId();
        String first = api.ok("POST", path, null, null, 200).path("readAt").asString();
        String second = api.ok("POST", path, null, null, 200).path("readAt").asString();
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("[TB-ENG-IBX-006] segna letto il messaggio di un altro membro: 404")
    void markReadOtherMember() {
        Fixture f = inboxFixture();
        assertThat(api.send("POST", "/v1/portal/inbox/" + f.unreadIds().getFirst() + "/read?memberId=" + freshId(), null, null)
                .status()).isEqualTo(404);
        assertThat(unread(f.memberId())).isEqualTo(3);
    }

    @Test
    @DisplayName("[TB-ENG-IBX-007] segna letto un id inesistente: 404")
    void markReadUnknown() {
        assertThat(api.send("POST", "/v1/portal/inbox/NON-ESISTE/read?memberId=" + freshId(), null, null).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("[TB-ENG-IBX-008] segna tutte come lette: marked = non letti INAPP, EMAIL_FAKE non toccato")
    void readAll() {
        Fixture f = inboxFixture();
        JsonNode r = api.ok("POST", "/v1/portal/inbox/read-all?memberId=" + f.memberId(), null, null, 200);
        assertThat(r.path("marked").asInt()).isEqualTo(3);
        assertThat(r.path("unread").asLong()).isZero();
        assertThat(unread(f.memberId())).isZero();
        assertThat(api.count("SELECT count(*) FROM inbox_message WHERE id = ? AND read_at IS NULL", f.emailId())).isEqualTo(1);
    }

    @Test
    @DisplayName("[TB-ENG-IBX-009] segna tutte come lette senza non letti: marked 0")
    void readAllNothing() {
        Fixture f = inboxFixture();
        api.ok("POST", "/v1/portal/inbox/read-all?memberId=" + f.memberId(), null, null, 200);
        assertThat(api.ok("POST", "/v1/portal/inbox/read-all?memberId=" + f.memberId(), null, null, 200).path("marked").asInt()).isZero();
    }

    @Test
    @DisplayName("[TB-ENG-IBX-010] inbox senza memberId: 400")
    void inboxWithoutMember() {
        assertThat(api.get("/v1/portal/inbox").status()).isEqualTo(400);
    }

    @Test
    @DisplayName("[TB-ENG-IBX-011] unread-count senza memberId: 400")
    void unreadWithoutMember() {
        assertThat(api.get("/v1/portal/inbox/unread-count").status()).isEqualTo(400);
    }

    @Test
    @DisplayName("[TB-ENG-IBX-012] read senza memberId: 400")
    void readWithoutMember() {
        Fixture f = inboxFixture();
        assertThat(api.send("POST", "/v1/portal/inbox/" + f.unreadIds().getFirst() + "/read", null, null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("[TB-ENG-IBX-013] read-all senza memberId: 400")
    void readAllWithoutMember() {
        assertThat(api.send("POST", "/v1/portal/inbox/read-all", null, Map.of()).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("[TB-ENG-IBX-014] read-all con memberId nel corpo: 200")
    void readAllMemberInBody() {
        // TESTBOOK: ambiguo, vedi TB-ENG-IBX-014.
        Fixture f = inboxFixture();
        assertThat(api.ok("POST", "/v1/portal/inbox/read-all", null, Map.of("memberId", f.memberId()), 200).path("marked").asInt())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("[TB-ENG-IBX-015] paginazione size=2 su 4 messaggi")
    void inboxPaging() {
        Fixture f = inboxFixture();
        JsonNode page = api.ok("GET", "/v1/portal/inbox?size=2&memberId=" + f.memberId(), null, null, 200);
        assertThat(page.path("items").size()).isEqualTo(2);
        assertThat(page.path("page").path("totalItems").asLong()).isEqualTo(4);
        assertThat(page.path("page").path("totalPages").asInt()).isEqualTo(2);
        assertThat(page.path("page").path("size").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("[TB-ENG-IBX-016] size=500 ridotto a 100")
    void inboxSizeCap() {
        Fixture f = inboxFixture();
        assertThat(api.ok("GET", "/v1/portal/inbox?size=500&memberId=" + f.memberId(), null, null, 200).path("page").path("size").asInt())
                .isEqualTo(100);
    }

    @Test
    @DisplayName("[TB-ENG-IBX-017] registro /v1/messages: tutti i canali, filtri channel e category")
    void messageLog() {
        Fixture f = inboxFixture();
        assertThat(api.get("/v1/messages?memberId=" + f.memberId()).body().path("page").path("totalItems").asLong()).isEqualTo(5);
        assertThat(api.get("/v1/messages?channel=EMAIL_FAKE&memberId=" + f.memberId()).body().path("page").path("totalItems").asLong())
                .isEqualTo(1);
        assertThat(api.get("/v1/messages?category=TIER&memberId=" + f.memberId()).body().path("page").path("totalItems").asLong())
                .isEqualTo(1);
    }

    // ================================================================= CLN

    @Test
    @DisplayName("[TB-ENG-CLN-001] messaggio dell'inbox di 180 giorni + 1 s: eliminato")
    void purgeInboxOld() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String m = freshId();
        String id = inbox(m, "INAPP", "PROGRAM", "Vecchio", now.minus(Duration.ofDays(180)).minusSeconds(1), null);
        jobs(now).purgeInbox();
        assertThat(api.count("SELECT count(*) FROM inbox_message WHERE id = ?", id)).isZero();
    }

    @Test
    @DisplayName("[TB-ENG-CLN-002] messaggio dell'inbox di 180 giorni esatti: conservato")
    void purgeInboxBoundary() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String m = freshId();
        String id = inbox(m, "INAPP", "PROGRAM", "Limite", now.minus(Duration.ofDays(180)), null);
        jobs(now).purgeInbox();
        assertThat(api.count("SELECT count(*) FROM inbox_message WHERE id = ?", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("[TB-ENG-CLN-003] vista di pop-up di 91 giorni fa: eliminata")
    void purgePopupViewOld() {
        Instant now = Instant.now();
        String m = freshId();
        popupView(m, LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(91));
        jobs(now).purgeInbox();
        assertThat(api.count("SELECT count(*) FROM popup_view WHERE member_id = ?", m)).isZero();
    }

    @Test
    @DisplayName("[TB-ENG-CLN-004] vista di pop-up di 90 giorni fa: conservata")
    void purgePopupViewBoundary() {
        Instant now = Instant.now();
        String m = freshId();
        popupView(m, LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(90));
        jobs(now).purgeInbox();
        assertThat(api.count("SELECT count(*) FROM popup_view WHERE member_id = ?", m)).isEqualTo(1);
    }

    @Test
    @DisplayName("[TB-ENG-CLN-005] consegna webhook di 14 giorni + 1 s: eliminata")
    void purgeDeliveryOld() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String id = delivery(now.minus(Duration.ofDays(14)).minusSeconds(1));
        jobs(now).purgeInbox();
        assertThat(api.count("SELECT count(*) FROM webhook_delivery WHERE id = ?", id)).isZero();
    }

    @Test
    @DisplayName("[TB-ENG-CLN-006] consegna webhook di 13 giorni: conservata")
    void purgeDeliveryRecent() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String id = delivery(now.minus(Duration.ofDays(13)));
        jobs(now).purgeInbox();
        assertThat(api.count("SELECT count(*) FROM webhook_delivery WHERE id = ?", id)).isEqualTo(1);
    }

    // ================================================================= AUDT

    @Test
    @DisplayName("[TB-ENG-AUDT-005] creazione di un template: audit CREATE")
    void auditTemplate() {
        String code = template(code("MSG"), "INAPP", "Titolo", "Testo");
        List<JsonNode> a = audits("MESSAGE_TEMPLATE:" + code);
        assertThat(a).hasSize(1);
        assertThat(a.getFirst().path("data").path("action").asString()).isEqualTo("CREATE");
        assertThat(a.getFirst().path("lhactor").asString()).isEqualTo(ADMIN);
    }

    @Test
    @DisplayName("[TB-ENG-AUDT-006] creazione di una regola: audit CREATE")
    void auditRule() {
        String code = code("NR");
        rule(code, "edition.closed", null, false);
        List<JsonNode> a = audits("NOTIFICATION_RULE:" + code);
        assertThat(a).hasSize(1);
        assertThat(a.getFirst().path("data").path("action").asString()).isEqualTo("CREATE");
    }

    // ================================================================= SNP

    @Test
    @DisplayName("[TB-ENG-SNP-001] member.registered: snapshot con nome e stato")
    void snapshotRegistered() {
        String m = freshId();
        publishAndWait(m, "member.registered", Map.of("firstName", "Ada", "status", "ACTIVE", "registeredAt", Instant.now().toString()));
        assertThat(snapshot(m, "first_name")).isEqualTo("Ada");
        assertThat(snapshot(m, "status")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("[TB-ENG-SNP-002] member.status.changed BLOCKED: stato dello snapshot")
    void snapshotStatus() {
        String m = member("ACTIVE", "Ada");
        publishAndWait(m, "member.status.changed", Map.of("previousStatus", "ACTIVE", "newStatus", "BLOCKED"));
        assertThat(snapshot(m, "status")).isEqualTo("BLOCKED");
    }

    @Test
    @DisplayName("[TB-ENG-SNP-003] tier.upgraded GOLD: livello dello snapshot")
    void snapshotTierUp() {
        String m = member("ACTIVE", "Ada");
        publishAndWait(m, "tier.upgraded", Map.of("previousTier", "SILVER", "newTier", "GOLD"));
        assertThat(snapshot(m, "tier_code")).isEqualTo("GOLD");
    }

    @Test
    @DisplayName("[TB-ENG-SNP-004] tier.downgraded SILVER: livello dello snapshot")
    void snapshotTierDown() {
        String m = member("ACTIVE", "Ada");
        publishAndWait(m, "tier.downgraded", Map.of("previousTier", "GOLD", "newTier", "SILVER"));
        assertThat(snapshot(m, "tier_code")).isEqualTo("SILVER");
    }

    @Test
    @DisplayName("[TB-ENG-SNP-005] tier.retained PLATINUM: livello dello snapshot")
    void snapshotTierRetained() {
        String m = member("ACTIVE", "Ada");
        publishAndWait(m, "tier.retained", Map.of("tier", "PLATINUM"));
        assertThat(snapshot(m, "tier_code")).isEqualTo("PLATINUM");
    }

    @Test
    @DisplayName("[TB-ENG-SNP-006] member.segment.entered: segmento aggiunto")
    void snapshotSegmentEntered() {
        String m = member("ACTIVE", "Ada");
        publishAndWait(m, "member.segment.entered", Map.of("segmentCode", "SEG-X"));
        assertThat(snapshot(m, "array_to_string(segments, ',')")).isEqualTo("SEG-X");
    }

    @Test
    @DisplayName("[TB-ENG-SNP-007] member.segment.left: segmento tolto")
    void snapshotSegmentLeft() {
        String m = member("ACTIVE", "Ada");
        publishAndWait(m, "member.segment.entered", Map.of("segmentCode", "SEG-X"));
        publishAndWait(m, "member.segment.left", Map.of("segmentCode", "SEG-X"));
        assertThat(snapshot(m, "array_to_string(segments, ',')")).isEmpty();
    }

    @Test
    @DisplayName("[TB-ENG-SNP-008] anonimizzazione: nome sostituito nei messaggi, snapshot senza nome")
    void snapshotAnonymized() {
        String m = member("ACTIVE", "Adalgisa");
        String id = eventId();
        api.publish(FACTS, m, TestbookApi.event(id, FACT + RUL_TYPES.get("NONE/ON"), m, Map.of("kind", "X")));
        api.awaitProcessed(id);
        assertThat(message(m, id).path("body").asString()).isEqualTo("Ciao Adalgisa");
        publishAndWait(m, "member.status.changed", Map.of("previousStatus", "ACTIVE", "newStatus", "ANONYMIZED"));
        String body = message(m, id).path("body").asString();
        assertThat(body).doesNotContain("Adalgisa").contains("Membro anonimo");
        assertThat(snapshot(m, "first_name")).isNull();
        assertThat(snapshot(m, "status")).isEqualTo("ANONYMIZED");
    }

    // ================================================================= helper

    private record Fixture(String memberId, List<String> unreadIds, String emailId) {
    }

    /** Membro nuovo con 3 INAPP non letti (M1…M3), 1 INAPP letto (M4, il più recente), 1 EMAIL_FAKE non letto. */
    private Fixture inboxFixture() {
        String m = freshId();
        Instant t = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
        List<String> unread = new ArrayList<>();
        unread.add(inbox(m, "INAPP", "POINTS", "M1", t, null));
        unread.add(inbox(m, "INAPP", "TIER", "M2", t.plusSeconds(60), null));
        unread.add(inbox(m, "INAPP", "REWARD", "M3", t.plusSeconds(120), null));
        inbox(m, "INAPP", "GAME", "M4 letto", t.plusSeconds(180), t.plusSeconds(200));
        String email = inbox(m, "EMAIL_FAKE", "PROGRAM", "E-mail", t.plusSeconds(240), null);
        return new Fixture(m, unread, email);
    }

    private String inbox(String memberId, String channel, String category, String title, Instant createdAt, Instant readAt) {
        String id = "IBX-TB-" + seq.incrementAndGet();
        jdbc.sql("""
                        INSERT INTO inbox_message (id, member_id, template_code, channel, title, body, category, source_event_id,
                                                   created_at, read_at)
                        VALUES (?, ?, 'MSG-TB-RUL', ?, ?, 'Testo', ?, ?, ?, ?)
                        """)
                .params(id, memberId, channel, title, category, "SRC-" + id, Timestamp.from(createdAt),
                        readAt == null ? null : Timestamp.from(readAt))
                .update();
        return id;
    }

    private void popupView(String memberId, LocalDate day) {
        jdbc.sql("INSERT INTO popup_view (content_id, member_id, view_date, seen_at) VALUES ('POP-TB-CLN', ?, ?, now())")
                .params(memberId, Date.valueOf(day)).update();
    }

    private String delivery(Instant createdAt) {
        String webhookId = api.ok("GET", "/v1/webhooks/WH-CRM-DEMO", null, null, 200).path("id").asString();
        String id = "WHD-TB-" + seq.incrementAndGet();
        jdbc.sql("""
                        INSERT INTO webhook_delivery (id, webhook_id, event_id, fact_type, payload, signature, attempt, status, created_at)
                        VALUES (?, ?, ?, 'tier.upgraded', '{}', 'sha256=x', 1, 'OK', ?)
                        """)
                .params(id, webhookId, "EVT-" + id, Timestamp.from(createdAt)).update();
        return id;
    }

    private EngagementJobs jobs(Instant now) {
        return new EngagementJobs(inboxRepository, popupViews, contents, webhooks, Clock.fixed(now, ZoneOffset.UTC));
    }

    private String template(String code, String channel, String title, String body) {
        Map<String, Object> t = templateBody(code, channel, "PROGRAM");
        t.put("titleTpl", title);
        t.put("bodyTpl", body);
        api.ok("POST", "/v1/message-templates", ADMIN, t, 201);
        return code;
    }

    private static Map<String, Object> templateBody(String code, String channel, String category) {
        Map<String, Object> t = new HashMap<>();
        t.put("code", code);
        t.put("name", "Template testbook");
        t.put("channel", channel);
        t.put("category", category);
        t.put("titleTpl", "Titolo");
        t.put("bodyTpl", "Testo");
        return t;
    }

    private void rule(String code, String factType, Map<String, Object> condition, boolean enabled) {
        rule(code, factType, condition, enabled, "MSG-TB-RUL");
    }

    private void rule(String code, String factType, Map<String, Object> condition, boolean enabled, String template) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", code);
        r.put("factType", factType);
        r.put("templateCode", template);
        r.put("enabled", enabled);
        if (condition != null) {
            r.put("condition", condition);
        }
        api.ok("POST", "/v1/notification-rules", ADMIN, r, 201);
    }

    private void assertRuleInvalid(Map<String, Object> body, String field) {
        Map<String, Object> b = new HashMap<>(body);
        b.put("code", code("NR"));
        Resp r = api.send("POST", "/v1/notification-rules", ADMIN, b);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("RULE_INVALID");
        assertThat(r.fields()).contains(field);
    }

    private void assertTemplateInvalid(Map<String, Object> body, String field) {
        Resp r = api.send("POST", "/v1/message-templates", ADMIN, body);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("TEMPLATE_INVALID");
        assertThat(r.fields()).contains(field);
    }

    /** Fatto senza regole sulla stessa partizione: quando è elaborato, i precedenti del membro lo sono già. */
    private void sentinel(String memberId) {
        String id = eventId();
        api.publish(FACTS, memberId, TestbookApi.event(id, FACT + NO_RULE_TYPE, memberId, Map.of("kind", "S")));
        api.awaitProcessed(id);
    }

    private long messages(String memberId, String sourceEventId) {
        return api.count("SELECT count(*) FROM inbox_message WHERE member_id = ? AND source_event_id = ?", memberId, sourceEventId);
    }

    private JsonNode message(String memberId, String sourceEventId) {
        for (JsonNode i : api.ok("GET", "/v1/messages?size=100&memberId=" + memberId, null, null, 200).path("items")) {
            if (sourceEventId.equals(i.path("sourceEventId").asString())) {
                return i;
            }
        }
        throw new AssertionError("messaggio assente per " + sourceEventId);
    }

    private List<JsonNode> outbox(String type, String causationId) {
        return jdbc.sql("SELECT payload::text AS p FROM outbox WHERE type = ? AND payload->>'lhcausationid' = ?")
                .params(type, causationId)
                .query((rs, n) -> TestbookApi.MAPPER.readTree(rs.getString("p"))).list();
    }

    private List<JsonNode> audits(String subject) {
        return jdbc.sql("SELECT payload::text AS p FROM outbox WHERE type = 'io.loyaltyhub.audit.entry' AND msg_key = ? ORDER BY created_at")
                .param(subject)
                .query((rs, n) -> TestbookApi.MAPPER.readTree(rs.getString("p"))).list();
    }

    /** Codice d'errore ({@code lh-error-code}) del record in DLQ per l'evento, atteso con scadenza. */
    private String dlqErrorCode(String eventId) {
        try (org.apache.kafka.clients.consumer.KafkaConsumer<String, String> consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<>(
                Map.of("bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                        "group.id", "tb-eng-dlq-" + java.util.UUID.randomUUID(), "auto.offset.reset", "earliest",
                        "key.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class,
                        "value.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class))) {
            consumer.subscribe(List.of("lh.dlq.v1"));
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline) {
                for (var r : consumer.poll(Duration.ofMillis(300))) {
                    if (r.value() != null && r.value().contains(eventId)) {
                        var h = r.headers().lastHeader("lh-error-code");
                        return h == null ? null : new String(h.value(), java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
            }
        }
        throw new AssertionError("nessun record in DLQ per " + eventId);
    }

    private void publishAndWait(String memberId, String type, Map<String, Object> data) {
        String id = eventId();
        api.publish(FACTS, memberId, TestbookApi.event(id, FACT + type, memberId, data));
        api.awaitProcessed(id);
    }

    private String snapshot(String memberId, String column) {
        return jdbc.sql("SELECT " + column + " AS v FROM engagement_member_snapshot WHERE member_id = ?").param(memberId)
                .query((rs, n) -> rs.getString("v")).list().getFirst();
    }

    private long unread(String memberId) {
        return api.ok("GET", "/v1/portal/inbox/unread-count?memberId=" + memberId, null, null, 200).path("unread").asLong();
    }

    private String member(String status, String firstName) {
        String id = freshId();
        jdbc.sql("INSERT INTO engagement_member_snapshot (member_id, first_name, status, tier_code) VALUES (?, ?, ?, 'BASE')")
                .params(id, firstName, status).update();
        return id;
    }

    private String freshId() {
        return "MBR-8" + String.format("%05d", seq.incrementAndGet());
    }

    private String eventId() {
        return "EVT-TB-" + java.util.UUID.randomUUID();
    }

    private String code(String prefix) {
        return prefix + "-TB-" + seq.incrementAndGet();
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
