package io.loyaltyhub.engagement;

import com.sun.net.httpserver.HttpServer;
import io.loyaltyhub.engagement.TestbookApi.Resp;
import io.loyaltyhub.engagement.application.WebhookDispatcher;
import io.loyaltyhub.engagement.domain.WebhookSignature;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-ENG (docs/testbook/TB-ENG-engagement.md), webhook in uscita: abbinamento dei fatti alle sottoscrizioni
 * (WSUB, tabella completa sottoscritto × attivo × doppio invio), invio firmato, ritenti a 1, 5, 15 minuti fino a
 * {@code GAVE_UP}, *Riprova* manuale, evento di prova, gestione e validazioni (WDLV), ruoli (WROL). Ricevitore
 * {@code HttpServer} del JDK su localhost con un percorso per riga (profilo come {@code local}: http://localhost ammesso,
 * nessun blocco degli indirizzi privati; il blocco è provato in TestbookEngWebhookTest). Scheduler automatico spento: i
 * giri si lanciano dal test con l'istante voluto. EmbeddedKafka + Zonky.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TestbookEngWebhookIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String ADMIN = "ADMIN:marta.admin";
    private static final String FACT = "io.loyaltyhub.fact.";
    private static final String FACTS = "lh.facts.v1";

    /** Richiesta ricevuta dal finto endpoint. */
    record Received(String key, Map<String, String> headers, byte[] body) {
        String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> responses = new ConcurrentHashMap<>();
    private final AtomicInteger followed = new AtomicInteger();
    private HttpServer receiver;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private WebhookDispatcher dispatcher;

    private TestbookApi api;
    private final AtomicInteger seq = new AtomicInteger();

    /** Stato condiviso dalla sequenza ordinata dei ritenti (WDLV-002…008). */
    private String chainDelivery;
    private String chainSecret;
    private JsonNode chainLast;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=engagement");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
        registry.add("loyaltyhub.webhooks.allow-http-localhost", () -> "true");
        registry.add("loyaltyhub.webhooks.block-private-addresses", () -> "false");
        registry.add("loyaltyhub.webhooks.dispatcher.enabled", () -> "false");
    }

    @BeforeAll
    void setUp() throws Exception {
        api = new TestbookApi(port, jdbc);
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/hook/", exchange -> {
            String key = exchange.getRequestURI().getPath().substring("/hook/".length());
            Map<String, String> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.getFirst()));
            received.add(new Received(key, headers, exchange.getRequestBody().readAllBytes()));
            int status = responses.getOrDefault(key, 204);
            if (status >= 300 && status < 400) {
                exchange.getResponseHeaders().add("Location", "/target");
            }
            byte[] out = status >= 400 ? "errore simulato".getBytes(StandardCharsets.UTF_8) : new byte[0];
            exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                exchange.getResponseBody().write(out);
            }
            exchange.close();
        });
        receiver.createContext("/target", exchange -> {
            followed.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        receiver.start();
    }

    @AfterAll
    void tearDown() throws Exception {
        receiver.stop(0);
        api.close();
        PG.close();
    }

    // ================================================================= WSUB

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/engagement/wsub.csv", numLinesToSkip = 1, delimiter = '\t', quoteCharacter = '~',
            maxCharsPerColumn = 8192)
    void subscriptionMatching(ArgumentsAccessor row) throws Exception {
        String[] c = TestbookRows.columns(row);
        boolean subscribed = "YES".equals(c[2]);
        boolean enabled = "ON".equals(c[3]);
        boolean duplicate = "YES".equals(c[4]);
        JsonNode hook = webhook(key(), List.of("coupon.used"), enabled);
        String member = member();
        String id = eventId();
        Map<String, Object> e = TestbookApi.event(id, FACT + (subscribed ? "coupon.used" : "badge.awarded"), member,
                Map.of("couponCode", "CAF-TB"));
        api.publish(FACTS, member, e);
        if (duplicate) {
            api.publish(FACTS, member, e);
        }
        api.awaitProcessed(id);
        sentinel(member);
        assertThat(deliveries(hook.path("id").asString(), id)).isEqualTo(Long.parseLong(c[5]));
    }

    @Test
    @DisplayName("[TB-ENG-WSUB-009] fatto message.delivered: nessuna consegna")
    void messageDeliveredNeverDelivered() {
        webhook(key(), List.of("coupon.used"), true);
        String member = member();
        String id = eventId();
        api.publish(FACTS, member, TestbookApi.event(id, FACT + "message.delivered", member,
                Map.of("templateCode", "MSG-WELCOME", "channel", "INAPP", "inboxMessageId", "X")));
        sentinel(member);
        assertThat(api.count("SELECT count(*) FROM webhook_delivery WHERE event_id = ?", id)).isZero();
    }

    @Test
    @DisplayName("[TB-ENG-WSUB-010] due webhook attivi sullo stesso tipo: una consegna ciascuno")
    void twoWebhooksSameType() {
        JsonNode a = webhook(key(), List.of("achievement.completed"), true);
        JsonNode b = webhook(key(), List.of("achievement.completed"), true);
        String member = member();
        String id = eventId();
        api.publish(FACTS, member, TestbookApi.event(id, FACT + "achievement.completed", member, Map.of("achievementCode", "ACH-TB")));
        api.awaitProcessed(id);
        assertThat(deliveries(a.path("id").asString(), id)).isEqualTo(1);
        assertThat(deliveries(b.path("id").asString(), id)).isEqualTo(1);
    }

    @Test
    @DisplayName("[TB-ENG-WSUB-011] consegna creata dal fatto: PENDING, corpo = CloudEvent originale firmato, nessun HTTP")
    void deliveryIsOriginalEvent() {
        JsonNode hook = webhook(key(), List.of("reward.redemption.cancelled"), true);
        String member = member();
        String id = eventId();
        Map<String, Object> e = TestbookApi.event(id, FACT + "reward.redemption.cancelled", member, Map.of("redemptionId", "RED-TB"));
        api.publish(FACTS, member, e);
        api.awaitProcessed(id);
        JsonNode d = deliveryOf(hook.path("code").asString(), id);
        assertThat(d.path("status").asString()).isEqualTo("PENDING");
        assertThat(d.path("attempt").asInt()).isZero();
        JsonNode payload = TestbookApi.MAPPER.readTree(d.path("payload").asString());
        assertThat(payload.path("id").asString()).isEqualTo(id);
        assertThat(payload.path("type").asString()).isEqualTo(FACT + "reward.redemption.cancelled");
        assertThat(payload.path("subject").asString()).isEqualTo("member:" + member);
        assertThat(payload.path("data").path("redemptionId").asString()).isEqualTo("RED-TB");
        assertThat(WebhookSignature.verify(hook.path("secret").asString(),
                d.path("payload").asString().getBytes(StandardCharsets.UTF_8), d.path("signature").asString())).isTrue();
        assertThat(requests(d.path("id").asString())).isEmpty();
    }

    // ================================================================= WDLV: ritenti (sequenza ordinata)

    @Test
    @Order(1)
    @DisplayName("[TB-ENG-WDLV-002] endpoint 500, primo tentativo: FAILED, prossimo a +1 min")
    void retryFirstAttempt() {
        String key = key();
        responses.put(key, 500);
        JsonNode hook = webhook(key, List.of("wallet.points.expired"), true);
        chainSecret = hook.path("secret").asString();
        String member = member();
        String id = eventId();
        api.publish(FACTS, member, TestbookApi.event(id, FACT + "wallet.points.expired", member, Map.of("amount", 10)));
        api.awaitProcessed(id);
        chainDelivery = deliveryOf(hook.path("code").asString(), id).path("id").asString();
        dispatcher.deliverDue(Instant.now());
        chainLast = delivery(chainDelivery);
        assertThat(chainLast.path("status").asString()).isEqualTo("FAILED");
        assertThat(chainLast.path("attempt").asInt()).isEqualTo(1);
        assertThat(chainLast.path("httpStatus").asInt()).isEqualTo(500);
        assertNextAfter(chainLast, Duration.ofMinutes(1));
    }

    @Test
    @Order(2)
    @DisplayName("[TB-ENG-WDLV-003] giro 1 s prima della scadenza: nessun tentativo")
    void retryNotDueYet() {
        Instant next = Instant.parse(chainLast.path("nextAttemptAt").asString());
        dispatcher.deliverDue(next.minusSeconds(1));
        assertThat(delivery(chainDelivery).path("attempt").asInt()).isEqualTo(1);
        assertThat(requests(chainDelivery)).hasSize(1);
    }

    @Test
    @Order(3)
    @DisplayName("[TB-ENG-WDLV-004] giro alla scadenza: tentativo 2, FAILED, prossimo a +5 min")
    void retrySecondAttempt() {
        chainLast = runAt(Instant.parse(chainLast.path("nextAttemptAt").asString()));
        assertThat(chainLast.path("attempt").asInt()).isEqualTo(2);
        assertThat(chainLast.path("status").asString()).isEqualTo("FAILED");
        assertNextAfter(chainLast, Duration.ofMinutes(5));
    }

    @Test
    @Order(4)
    @DisplayName("[TB-ENG-WDLV-005] giro alla scadenza successiva: tentativo 3, prossimo a +15 min")
    void retryThirdAttempt() {
        chainLast = runAt(Instant.parse(chainLast.path("nextAttemptAt").asString()));
        assertThat(chainLast.path("attempt").asInt()).isEqualTo(3);
        assertThat(chainLast.path("status").asString()).isEqualTo("FAILED");
        assertNextAfter(chainLast, Duration.ofMinutes(15));
    }

    @Test
    @Order(5)
    @DisplayName("[TB-ENG-WDLV-006] giro dopo altri 15 min: tentativo 4, GAVE_UP")
    void retryGaveUp() {
        chainLast = runAt(Instant.parse(chainLast.path("nextAttemptAt").asString()));
        assertThat(chainLast.path("attempt").asInt()).isEqualTo(4);
        assertThat(chainLast.path("status").asString()).isEqualTo("GAVE_UP");
        assertThat(chainLast.path("nextAttemptAt").isMissingNode() || chainLast.path("nextAttemptAt").isNull()).isTrue();
    }

    @Test
    @Order(6)
    @DisplayName("[TB-ENG-WDLV-007] giro un giorno dopo il GAVE_UP: nessun altro tentativo")
    void retryNoMoreAfterGaveUp() {
        dispatcher.deliverDue(Instant.now().plus(Duration.ofDays(1)));
        assertThat(delivery(chainDelivery).path("attempt").asInt()).isEqualTo(4);
        assertThat(requests(chainDelivery)).hasSize(4);
    }

    @Test
    @Order(7)
    @DisplayName("[TB-ENG-WDLV-008] i 4 tentativi: stesso corpo, stesso evento, firma verificabile")
    void retrySameBodyAndSignature() {
        List<Received> calls = requests(chainDelivery);
        assertThat(calls).hasSize(4);
        String eventId = calls.getFirst().header("X-LH-Event-Id");
        for (Received r : calls) {
            assertThat(r.header("X-LH-Event-Id")).isEqualTo(eventId);
            assertThat(r.body()).isEqualTo(calls.getFirst().body());
            assertThat(WebhookSignature.verify(chainSecret, r.body(), r.header("X-LH-Signature"))).isTrue();
        }
    }

    // ================================================================= WDLV: invio, Riprova, prova, gestione

    @Test
    @DisplayName("[TB-ENG-WDLV-001] invio a un endpoint che risponde 204: OK e header firmati")
    void sendSigned() {
        String key = key();
        JsonNode hook = webhook(key, List.of("contest.won"), true);
        String member = member();
        String id = eventId();
        api.publish(FACTS, member, TestbookApi.event(id, FACT + "contest.won", member, Map.of("contestCode", "IW-TB")));
        api.awaitProcessed(id);
        String deliveryId = deliveryOf(hook.path("code").asString(), id).path("id").asString();
        dispatcher.deliverDue(Instant.now());
        JsonNode d = delivery(deliveryId);
        assertThat(d.path("status").asString()).isEqualTo("OK");
        assertThat(d.path("attempt").asInt()).isEqualTo(1);
        assertThat(d.path("httpStatus").asInt()).isEqualTo(204);
        List<Received> calls = requests(deliveryId);
        assertThat(calls).hasSize(1);
        Received call = calls.getFirst();
        assertThat(call.header("X-LH-Event-Id")).isEqualTo(id);
        assertThat(call.header("X-LH-Signature")).startsWith("sha256=");
        assertThat(WebhookSignature.verify(hook.path("secret").asString(), call.body(), call.header("X-LH-Signature"))).isTrue();
        assertThat(TestbookApi.MAPPER.readTree(call.body()).path("id").asString()).isEqualTo(id);
    }

    @Test
    @DisplayName("[TB-ENG-WDLV-009] Riprova su GAVE_UP ancora in errore: tentativo 5, di nuovo GAVE_UP")
    void manualRetryAfterGaveUpFails() {
        String key = key();
        responses.put(key, 500);
        String id = gaveUp(key);
        JsonNode r = api.ok("POST", "/v1/webhook-deliveries/" + id + "/retry", ADMIN, null, 200);
        assertThat(r.path("attempt").asInt()).isEqualTo(5);
        assertThat(r.path("status").asString()).isEqualTo("GAVE_UP");
        assertThat(r.path("nextAttemptAt").isMissingNode() || r.path("nextAttemptAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("[TB-ENG-WDLV-010] Riprova su GAVE_UP con endpoint tornato su: OK")
    void manualRetryAfterGaveUpSucceeds() {
        String key = key();
        responses.put(key, 500);
        String id = gaveUp(key);
        responses.put(key, 200);
        JsonNode r = api.ok("POST", "/v1/webhook-deliveries/" + id + "/retry", ADMIN, null, 200);
        assertThat(r.path("status").asString()).isEqualTo("OK");
        assertThat(r.path("httpStatus").asInt()).isEqualTo(200);
    }

    @Test
    @DisplayName("[TB-ENG-WDLV-011] Riprova su FAILED ancora in errore: tentativo 2, prossimo a +5 min")
    void manualRetryOnFailed() {
        String key = key();
        responses.put(key, 500);
        JsonNode hook = webhook(key, List.of("wallet.points.earned"), true);
        JsonNode first = api.ok("POST", "/v1/webhooks/" + hook.path("code").asString() + "/test", ADMIN, null, 201);
        assertThat(first.path("status").asString()).isEqualTo("FAILED");
        JsonNode r = api.ok("POST", "/v1/webhook-deliveries/" + first.path("id").asString() + "/retry", ADMIN, null, 200);
        assertThat(r.path("attempt").asInt()).isEqualTo(2);
        assertThat(r.path("status").asString()).isEqualTo("FAILED");
        assertNextAfter(r, Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("[TB-ENG-WDLV-012] Riprova su OK: 409 DELIVERY_NOT_RETRYABLE")
    void manualRetryOnOk() {
        JsonNode hook = webhook(key(), List.of("wallet.points.earned"), true);
        JsonNode ok = api.ok("POST", "/v1/webhooks/" + hook.path("code").asString() + "/test", ADMIN, null, 201);
        assertThat(ok.path("status").asString()).isEqualTo("OK");
        Resp r = api.send("POST", "/v1/webhook-deliveries/" + ok.path("id").asString() + "/retry", ADMIN, null);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("DELIVERY_NOT_RETRYABLE");
    }

    @Test
    @DisplayName("[TB-ENG-WDLV-013] Riprova su PENDING: 409 DELIVERY_NOT_RETRYABLE")
    void manualRetryOnPending() {
        // TESTBOOK: ambiguo, vedi TB-ENG-WDLV-013.
        JsonNode hook = webhook(key(), List.of("wallet.points.refunded"), true);
        String member = member();
        String id = eventId();
        api.publish(FACTS, member, TestbookApi.event(id, FACT + "wallet.points.refunded", member, Map.of("amount", 5)));
        api.awaitProcessed(id);
        String deliveryId = deliveryOf(hook.path("code").asString(), id).path("id").asString();
        Resp r = api.send("POST", "/v1/webhook-deliveries/" + deliveryId + "/retry", ADMIN, null);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("DELIVERY_NOT_RETRYABLE");
    }

    @Test
    @DisplayName("[TB-ENG-WDLV-014] Riprova su una consegna inesistente: 404")
    void manualRetryUnknown() {
        assertThat(api.send("POST", "/v1/webhook-deliveries/NON-ESISTE/retry", ADMIN, null).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("[TB-ENG-WDLV-015] endpoint che risponde 302: FAILED, redirect non seguito")
    void redirectIsFailure() {
        String key = key();
        responses.put(key, 302);
        JsonNode hook = webhook(key, List.of("wallet.points.earned"), true);
        int before = followed.get();
        JsonNode d = api.ok("POST", "/v1/webhooks/" + hook.path("code").asString() + "/test", ADMIN, null, 201);
        assertThat(d.path("status").asString()).isEqualTo("FAILED");
        assertThat(d.path("httpStatus").asInt()).isEqualTo(302);
        assertThat(followed.get()).isEqualTo(before);
    }

    @Test
    @DisplayName("[TB-ENG-WDLV-016] Invia evento di prova: 201, test, esempio del contratto, lhactor di chi invia")
    void testEvent() {
        JsonNode hook = webhook(key(), List.of("wallet.points.earned"), true);
        JsonNode d = api.ok("POST", "/v1/webhooks/" + hook.path("code").asString() + "/test", ADMIN, null, 201);
        assertThat(d.path("test").asBoolean()).isTrue();
        JsonNode payload = TestbookApi.MAPPER.readTree(d.path("payload").asString());
        assertThat(payload.path("type").asString()).isEqualTo(FACT + "wallet.points.earned");
        assertThat(payload.path("data").path("amount").isNumber()).isTrue();
        assertThat(payload.path("lhactor").asString()).isEqualTo(ADMIN);
        assertThat(requests(d.path("id").asString())).hasSize(1);
    }

    @Test
    @DisplayName("[TB-ENG-WDLV-017] evento di prova su un webhook disattivato: inviato comunque")
    void testEventDisabled() {
        // TESTBOOK: ambiguo, vedi TB-ENG-WDLV-017.
        JsonNode hook = webhook(key(), List.of("wallet.points.earned"), false);
        JsonNode d = api.ok("POST", "/v1/webhooks/" + hook.path("code").asString() + "/test", ADMIN, null, 201);
        assertThat(requests(d.path("id").asString())).hasSize(1);
    }

    @Test
    @DisplayName("[TB-ENG-WDLV-018] segreto solo nella risposta di creazione")
    void secretOnlyOnCreate() {
        JsonNode hook = webhook(key(), List.of("tier.upgraded"), true);
        assertThat(hook.path("secret").asString()).startsWith("whsec_");
        assertThat(api.ok("GET", "/v1/webhooks/" + hook.path("code").asString(), null, null, 200).has("secret")).isFalse();
        for (JsonNode w : api.ok("GET", "/v1/webhooks", null, null, 200)) {
            assertThat(w.has("secret")).isFalse();
        }
    }

    @Test
    @DisplayName("[TB-ENG-WDLV-019] tipo di fatto sconosciuto: 422 factTypes")
    void unknownFactType() {
        assertWebhookInvalid(Map.of("name", "x", "url", url(key()), "factTypes", List.of("wallet.points.inventati")), "factTypes");
    }

    @Test
    @DisplayName("[TB-ENG-WDLV-020] sottoscrizione a message.delivered: 422 factTypes")
    void subscribeMessageDelivered() {
        assertWebhookInvalid(Map.of("name", "x", "url", url(key()), "factTypes", List.of("message.delivered")), "factTypes");
    }

    @Test
    @DisplayName("[TB-ENG-WDLV-021] nessun tipo di fatto: 422 factTypes")
    void noFactTypes() {
        // TESTBOOK: ambiguo, vedi TB-ENG-WDLV-021.
        assertWebhookInvalid(Map.of("name", "x", "url", url(key()), "factTypes", List.of()), "factTypes");
    }

    @Test
    @DisplayName("[TB-ENG-WDLV-022] URL http:// pubblico: 422 url")
    void httpUrlRejected() {
        assertWebhookInvalid(Map.of("name", "x", "url", "http://example.org/hook", "factTypes", List.of("tier.upgraded")), "url");
    }

    @Test
    @DisplayName("[TB-ENG-WDLV-023] nome assente: 422 name")
    void nameRequired() {
        // TESTBOOK: ambiguo, vedi TB-ENG-WDLV-023.
        assertWebhookInvalid(Map.of("url", url(key()), "factTypes", List.of("tier.upgraded")), "name");
    }

    @Test
    @DisplayName("[TB-ENG-WDLV-024] disattivazione con versione superata: 409 VERSION_CONFLICT")
    void versionConflict() {
        String code = webhook(key(), List.of("tier.upgraded"), true).path("code").asString();
        api.ok("PUT", "/v1/webhooks/" + code, ADMIN, Map.of("enabled", false, "version", 0), 200);
        Resp r = api.send("PUT", "/v1/webhooks/" + code, ADMIN, Map.of("enabled", true, "version", 0));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("VERSION_CONFLICT");
    }

    @Test
    @DisplayName("[TB-ENG-WDLV-025] eliminazione: 204, poi 404, registro eliminato")
    void deleteWebhook() {
        JsonNode hook = webhook(key(), List.of("tier.upgraded"), true);
        String code = hook.path("code").asString();
        api.ok("POST", "/v1/webhooks/" + code + "/test", ADMIN, null, 201);
        api.ok("DELETE", "/v1/webhooks/" + code, ADMIN, null, 204);
        assertThat(api.get("/v1/webhooks/" + code).status()).isEqualTo(404);
        assertThat(api.count("SELECT count(*) FROM webhook_delivery WHERE webhook_id = ?", hook.path("id").asString())).isZero();
    }

    @Test
    @DisplayName("[TB-ENG-WDLV-026] registro consegne con status sconosciuto: 400")
    void deliveriesBadStatus() {
        String code = webhook(key(), List.of("tier.upgraded"), true).path("code").asString();
        assertThat(api.get("/v1/webhooks/" + code + "/deliveries?status=BOH").status()).isEqualTo(400);
    }

    @Test
    @DisplayName("[TB-ENG-WDLV-027] job demo deliver-webhooks con MARKETING: 403")
    void demoJobAdminOnly() {
        assertThat(api.send("POST", "/v1/demo/jobs/deliver-webhooks", "MARKETING:luca.marketing", null).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("[TB-ENG-AUDT-007] creazione di un webhook: audit CREATE senza il segreto")
    void auditWebhook() {
        JsonNode hook = webhook(key(), List.of("tier.upgraded"), true);
        List<String> audits = jdbc.sql("SELECT payload::text FROM outbox WHERE type = 'io.loyaltyhub.audit.entry' AND msg_key = ?")
                .param("WEBHOOK:" + hook.path("code").asString()).query(String.class).list();
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst()).contains("\"CREATE\"").doesNotContain(hook.path("secret").asString());
    }

    @Test
    @DisplayName("[TB-ENG-SNP-009] anonimizzazione: consegna webhook senza il nome e rifirmata")
    void anonymizedDeliveryRedacted() {
        JsonNode hook = webhook(key(), List.of("member.registered"), true);
        String member = member();
        String id = eventId();
        api.publish(FACTS, member, TestbookApi.event(id, FACT + "member.registered", member,
                Map.of("firstName", "Adalgisa", "lastName", "Testbook", "email", "adalgisa@example.org", "status", "ACTIVE")));
        api.awaitProcessed(id);
        assertThat(deliveryOf(hook.path("code").asString(), id).path("payload").asString()).contains("Adalgisa");
        String anon = eventId();
        api.publish(FACTS, member, TestbookApi.event(anon, FACT + "member.status.changed", member,
                Map.of("previousStatus", "ACTIVE", "newStatus", "ANONYMIZED")));
        api.awaitProcessed(anon);
        JsonNode d = deliveryOf(hook.path("code").asString(), id);
        assertThat(d.path("payload").asString()).doesNotContain("Adalgisa").doesNotContain("adalgisa@example.org");
        assertThat(WebhookSignature.verify(hook.path("secret").asString(), d.path("payload").asString().getBytes(StandardCharsets.UTF_8),
                d.path("signature").asString())).isTrue();
    }

    @Test
    @DisplayName("[TB-ENG-WDLV-028] codice già usato: 409 CODE_TAKEN")
    void webhookCodeTaken() {
        String code = webhook(key(), List.of("tier.upgraded"), false).path("code").asString();
        Resp r = api.send("POST", "/v1/webhooks", ADMIN, Map.of("code", code, "name", "Doppio", "url", url(key()),
                "factTypes", List.of("tier.upgraded")));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("CODE_TAKEN");
    }

    @Test
    @DisplayName("[TB-ENG-WDLV-029] modifica che cambia il codice: 409 CODE_IMMUTABLE")
    void webhookCodeImmutable() {
        // TESTBOOK: ambiguo, vedi TB-ENG-WDLV-029.
        String code = webhook(key(), List.of("tier.upgraded"), false).path("code").asString();
        Resp r = api.send("PUT", "/v1/webhooks/" + code, ADMIN, Map.of("code", code + "-ALTRO"));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("CODE_IMMUTABLE");
    }

    @Test
    @DisplayName("[TB-ENG-WDLV-030] codice non valido: 422 code")
    void webhookInvalidCode() {
        assertWebhookInvalid(Map.of("code", "wh bad", "name", "x", "url", url(key()), "factTypes", List.of("tier.upgraded")), "code");
    }

    @Test
    @DisplayName("[TB-ENG-WDLV-031] Riprova su una consegna già in invio: 409 DELIVERY_BUSY")
    void manualRetryBusy() {
        // TESTBOOK: ambiguo, vedi TB-ENG-WDLV-031.
        String key = key();
        responses.put(key, 500);
        String id = gaveUp(key);
        jdbc.sql("UPDATE webhook_delivery SET claimed_until = now() + interval '5 minutes' WHERE id = ?").param(id).update();
        Resp r = api.send("POST", "/v1/webhook-deliveries/" + id + "/retry", ADMIN, null);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("DELIVERY_BUSY");
    }

    @Test
    @DisplayName("[TB-ENG-WROL-008] Invia evento di prova con MARKETING: 403")
    void testEventMarketing() {
        // TESTBOOK: ambiguo, vedi TB-ENG-WROL-008.
        String code = webhook(key(), List.of("tier.upgraded"), true).path("code").asString();
        assertThat(api.send("POST", "/v1/webhooks/" + code + "/test", "MARKETING:luca.marketing", null).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("[TB-ENG-WROL-009] Riprova con CARE: 403")
    void retryCare() {
        // TESTBOOK: ambiguo, vedi TB-ENG-WROL-009.
        String key = key();
        responses.put(key, 500);
        String id = gaveUp(key);
        assertThat(api.send("POST", "/v1/webhook-deliveries/" + id + "/retry", "CARE:paolo.care", null).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("[TB-ENG-WROL-010] DELETE con MARKETING: 403")
    void deleteMarketing() {
        // TESTBOOK: ambiguo, vedi TB-ENG-WROL-010.
        String code = webhook(key(), List.of("tier.upgraded"), false).path("code").asString();
        assertThat(api.send("DELETE", "/v1/webhooks/" + code, "MARKETING:luca.marketing", null).status()).isEqualTo(403);
        assertThat(api.get("/v1/webhooks/" + code).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("[TB-ENG-WROL-011] PUT con ANALYST: 403")
    void updateAnalyst() {
        String code = webhook(key(), List.of("tier.upgraded"), false).path("code").asString();
        assertThat(api.send("PUT", "/v1/webhooks/" + code, "ANALYST:sara.analyst", Map.of("enabled", true)).status()).isEqualTo(403);
    }

    // ================================================================= WROL

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/engagement/wrol.csv", numLinesToSkip = 1, delimiter = '\t', quoteCharacter = '~',
            maxCharsPerColumn = 8192)
    void roles(ArgumentsAccessor row) throws Exception {
        // TESTBOOK: ambiguo, vedi le righe WROL con MARKETING, LEGAL, CARE e intestazione non valida (webhook.write senza ●
        // in docs/08 §2).
        String[] c = TestbookRows.columns(row);
        String actor = "<none>".equals(c[2]) ? null : c[2];
        Resp r = api.send("POST", "/v1/webhooks", actor, Map.of("name", "Ruoli", "url", url(key()),
                "factTypes", List.of("tier.upgraded"), "enabled", false));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(Integer.parseInt(c[3]));
        if (r.status() == 403) {
            assertThat(r.code()).isEqualTo("FORBIDDEN_ROLE");
        }
    }

    // ================================================================= helper

    private String key() {
        return "k" + seq.incrementAndGet();
    }

    private String url(String key) {
        return "http://localhost:" + receiver.getAddress().getPort() + "/hook/" + key;
    }

    private JsonNode webhook(String key, List<String> types, boolean enabled) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "WH-TB-" + seq.incrementAndGet());
        body.put("name", "Webhook testbook");
        body.put("url", url(key));
        body.put("factTypes", types);
        body.put("enabled", enabled);
        return api.ok("POST", "/v1/webhooks", ADMIN, body, 201);
    }

    /** Consegna portata a GAVE_UP: evento di prova fallito e poi, come dopo i tre ritenti, 4 tentativi esauriti. */
    private String gaveUp(String key) {
        JsonNode hook = webhook(key, List.of("wallet.points.earned"), true);
        JsonNode d = api.ok("POST", "/v1/webhooks/" + hook.path("code").asString() + "/test", ADMIN, null, 201);
        jdbc.sql("UPDATE webhook_delivery SET status = 'GAVE_UP', attempt = 4, next_attempt_at = NULL WHERE id = ?")
                .param(d.path("id").asString()).update();
        return d.path("id").asString();
    }

    private void assertWebhookInvalid(Map<String, Object> body, String field) {
        Resp r = api.send("POST", "/v1/webhooks", ADMIN, body);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("WEBHOOK_INVALID");
        assertThat(r.fields()).contains(field);
    }

    private JsonNode runAt(Instant at) {
        dispatcher.deliverDue(at);
        return delivery(chainDelivery);
    }

    private static void assertNextAfter(JsonNode d, Duration delay) {
        Instant last = Instant.parse(d.path("lastAttemptAt").asString());
        assertThat(Instant.parse(d.path("nextAttemptAt").asString())).isEqualTo(last.plus(delay));
    }

    private JsonNode delivery(String id) {
        return api.ok("GET", "/v1/webhook-deliveries/" + id, null, null, 200);
    }

    private JsonNode deliveryOf(String webhookCode, String eventId) {
        for (JsonNode d : api.ok("GET", "/v1/webhooks/" + webhookCode + "/deliveries?size=100", null, null, 200).path("items")) {
            if (eventId.equals(d.path("eventId").asString())) {
                return d;
            }
        }
        throw new AssertionError("consegna assente per " + eventId);
    }

    private long deliveries(String webhookId, String eventId) {
        return api.count("SELECT count(*) FROM webhook_delivery WHERE webhook_id = ? AND event_id = ?", webhookId, eventId);
    }

    private List<Received> requests(String deliveryId) {
        return received.stream().filter(r -> deliveryId.equals(r.header("X-LH-Delivery-Id"))).toList();
    }

    private void sentinel(String member) {
        String id = eventId();
        api.publish(FACTS, member, TestbookApi.event(id, FACT + "wallet.points.released", member, Map.of("amount", 1)));
        api.awaitProcessed(id);
    }

    private String member() {
        return "MBR-7" + String.format("%05d", seq.incrementAndGet());
    }

    private static String eventId() {
        return "EVT-TB-" + java.util.UUID.randomUUID();
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
