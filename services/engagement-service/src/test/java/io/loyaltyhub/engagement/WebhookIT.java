package io.loyaltyhub.engagement;

import com.sun.net.httpserver.HttpServer;
import io.loyaltyhub.engagement.application.WebhookDispatcher;
import io.loyaltyhub.engagement.domain.WebhookSignature;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M7.2 webhook in uscita (docs/servizi/engagement-service.md §3, §5, §7; docs/12 §M7; F-WBH-01, BO-23): seed con un
 * webhook spento e il suo registro; ruoli e validazioni; segreto mostrato una volta; un fatto da Kafka diventa una
 * consegna {@code PENDING} (nessun HTTP nel consumer) che lo scheduler invia firmata e verificabile; endpoint che
 * risponde 500 → 3 ritenti a 1, 5, 15 minuti (tempo spostato con {@code asOf}) → {@code GAVE_UP}; *Riprova* manuale.
 * Il ricevitore è un {@code HttpServer} del JDK su localhost (ammesso con {@code allow-http-localhost}); lo scheduler
 * automatico è spento: i giri si lanciano dal test o dal job demo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebhookIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String ADMIN = "ADMIN:marta.admin";
    private static final String SEED_SECRET = "whsec_demo-club-aurora-solo-per-prove";

    /** Richiesta ricevuta dal finto endpoint. */
    record Received(Map<String, String> headers, byte[] body) {
        String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger respondWith = new AtomicInteger(204);
    private HttpServer receiver;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private WebhookDispatcher dispatcher;

    @Autowired
    private JdbcClient jdbc;

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
    void startReceiver() throws Exception {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/hook", exchange -> {
            Map<String, String> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.getFirst()));
            received.add(new Received(headers, exchange.getRequestBody().readAllBytes()));
            int status = respondWith.get();
            byte[] out = status >= 400 ? "errore simulato".getBytes(StandardCharsets.UTF_8) : new byte[0];
            exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                exchange.getResponseBody().write(out);
            }
            exchange.close();
        });
        receiver.start();
    }

    @AfterAll
    void tearDown() throws Exception {
        receiver.stop(0);
        PG.close();
    }

    private String hookUrl() {
        return "http://localhost:" + receiver.getAddress().getPort() + "/hook";
    }

    /** docs/10 §7: un webhook, disabilitato, verso https://example.org/hook; registro storico firmato col segreto del seed. */
    @Test
    void seedHasOneDisabledWebhookWithItsDeliveryLog() {
        JsonNode seeded = null;
        for (JsonNode w : get("/v1/webhooks")) {
            if (w.path("code").asString().equals("WH-CRM-DEMO")) {
                seeded = w;
            }
        }
        assertThat(seeded).isNotNull();
        assertThat(seeded.path("enabled").asBoolean()).isFalse();
        assertThat(seeded.path("url").asString()).isEqualTo("https://example.org/hook");
        assertThat(seeded.has("secret")).as("il segreto non si rilegge").isFalse();
        assertThat(seeded.path("stats").path("total").asInt()).isEqualTo(5);
        assertThat(seeded.path("stats").path("ok").asInt()).isEqualTo(3);
        assertThat(seeded.path("stats").path("gaveUp").asInt()).isEqualTo(2);

        JsonNode page = get("/v1/webhooks/WH-CRM-DEMO/deliveries?size=10");
        assertThat(page.path("page").path("totalItems").asInt()).isEqualTo(5);
        for (JsonNode d : page.path("items")) {
            assertThat(WebhookSignature.verify(SEED_SECRET, d.path("payload").asString().getBytes(StandardCharsets.UTF_8),
                    d.path("signature").asString())).isTrue();
            assertThat(d.path("status").asString()).isIn("OK", "GAVE_UP");
        }
        assertThat(get("/v1/webhooks/WH-CRM-DEMO/deliveries?status=GAVE_UP").path("items").size()).isEqualTo(2);
        assertThat(status("GET", "/v1/webhooks/WH-CRM-DEMO/deliveries?status=BOH", null, null)).isEqualTo(400);
    }

    @Test
    void writesAreAdminOnlyAndValidated() {
        Map<String, Object> ok = Map.of("name", "Ruoli", "url", "https://example.org/ruoli", "factTypes", List.of("tier.upgraded"));
        assertThat(status("POST", "/v1/webhooks", null, ok)).isEqualTo(403);
        assertThat(status("POST", "/v1/webhooks", "MARKETING:luca.marketing", ok)).isEqualTo(403);
        assertThat(status("POST", "/v1/webhooks", "LEGAL:giada.legal", ok)).isEqualTo(403);

        assertThat(fields(send("POST", "/v1/webhooks", ADMIN, Map.of("name", "x", "url", "http://example.org/hook",
                "factTypes", List.of("tier.upgraded")), 422))).containsExactly("url");
        assertThat(fields(send("POST", "/v1/webhooks", ADMIN, Map.of("name", "x", "url", "https://example.org/hook",
                "factTypes", List.of()), 422))).containsExactly("factTypes");
        assertThat(fields(send("POST", "/v1/webhooks", ADMIN, Map.of("name", "x", "url", "https://example.org/hook",
                "factTypes", List.of("message.delivered")), 422))).containsExactly("factTypes");
        JsonNode bad = send("POST", "/v1/webhooks", ADMIN, Map.of("url", "https://example.org/hook",
                "factTypes", List.of("wallet.points.inventati")), 422);
        assertThat(bad.path("code").asString()).isEqualTo("WEBHOOK_INVALID");
        assertThat(fields(bad)).containsExactlyInAnyOrder("name", "factTypes");
        send("POST", "/v1/webhooks", ADMIN, Map.of("code", "WH-IT-DUP", "name", "Uno", "url", "https://example.org/a",
                "factTypes", List.of("tier.upgraded")), 201);
        assertThat(send("POST", "/v1/webhooks", ADMIN, Map.of("code", "WH-IT-DUP", "name", "Due", "url", "https://example.org/b",
                "factTypes", List.of("tier.upgraded")), 409).path("code").asString()).isEqualTo("CODE_TAKEN");
    }

    /** Fatto da Kafka → consegna PENDING (niente HTTP nel consumer) → lo scheduler la invia firmata, verificabile. */
    @Test
    void factBecomesASignedDeliveryVerifiableByTheReceiver() throws Exception {
        respondWith.set(204);
        JsonNode created = send("POST", "/v1/webhooks", ADMIN, Map.of("code", "WH-IT-FACT", "name", "Fatti",
                "url", hookUrl(), "factTypes", List.of("io.loyaltyhub.fact.wallet.points.earned", "tier.upgraded")), 201);
        String secret = created.path("secret").asString();
        assertThat(secret).startsWith("whsec_");
        assertThat(created.path("factTypes").toString()).isEqualTo("[\"tier.upgraded\",\"wallet.points.earned\"]");
        assertThat(get("/v1/webhooks/WH-IT-FACT").has("secret")).isFalse();
        assertThat(audit("WEBHOOK:WH-IT-FACT")).as("audit di creazione").isNotEmpty()
                .allSatisfy(a -> assertThat(a).doesNotContain(secret));

        String eventId = "it-wh-" + UUID.randomUUID();
        Map<String, Object> event = fact(eventId, "io.loyaltyhub.fact.wallet.points.earned", "MBR-000002",
                Map.of("ledgerEntryId", "LED-IT-1", "currency", "PTS", "amount", 162, "balanceAfter", 2012));
        publish(event);
        publish(event); // doppio invio
        publish(fact("it-wh-" + UUID.randomUUID(), "io.loyaltyhub.fact.badge.awarded", "MBR-000002",
                Map.of("badgeCode", "BDG-TRIS", "origin", "ACHIEVEMENT"))); // tipo non sottoscritto
        publish(fact("it-wh-" + UUID.randomUUID(), "io.loyaltyhub.fact.message.delivered", "MBR-000002",
                Map.of("templateCode", "MSG-WELCOME"))); // mai consegnato

        JsonNode delivery = awaitDelivery("WH-IT-FACT", eventId);
        assertThat(delivery.path("status").asString()).isEqualTo("PENDING");
        assertThat(delivery.path("attempt").asInt()).isZero();
        assertThat(delivery.path("memberId").asString()).isEqualTo("MBR-000002");
        Thread.sleep(1_500); // lascia elaborare gli altri tre fatti
        assertThat(get("/v1/webhooks/WH-IT-FACT/deliveries").path("page").path("totalItems").asInt())
                .as("una sola consegna: doppione, tipo non sottoscritto e message.delivered esclusi").isEqualTo(1);
        assertThat(received(delivery.path("id").asString())).as("nessun HTTP dal consumer").isEmpty();

        dispatcher.deliverDue(Instant.now());
        List<Received> calls = received(delivery.path("id").asString());
        assertThat(calls).hasSize(1);
        Received call = calls.getFirst();
        assertThat(call.header("X-LH-Event-Id")).isEqualTo(eventId);
        assertThat(call.header("Content-Type")).startsWith("application/cloudevents+json");
        assertThat(WebhookSignature.verify(secret, call.body(), call.header("X-LH-Signature"))).isTrue();
        assertThat(WebhookSignature.verify("whsec_altro", call.body(), call.header("X-LH-Signature"))).isFalse();
        JsonNode body = mapper.readTree(call.body());
        assertThat(body.path("id").asString()).isEqualTo(eventId);
        assertThat(body.path("type").asString()).isEqualTo("io.loyaltyhub.fact.wallet.points.earned");
        assertThat(body.path("data").path("amount").asInt()).isEqualTo(162);
        assertThat(body.path("time").isString()).as("date RFC 3339").isTrue();
        verifyWithNodeScript(secret, call);

        JsonNode after = get("/v1/webhook-deliveries/" + delivery.path("id").asString());
        assertThat(after.path("status").asString()).isEqualTo("OK");
        assertThat(after.path("attempt").asInt()).isEqualTo(1);
        assertThat(after.path("httpStatus").asInt()).isEqualTo(204);
        assertThat(after.path("nextAttemptAt").isMissingNode() || after.path("nextAttemptAt").isNull()).isTrue();
        JsonNode stats = get("/v1/webhooks/WH-IT-FACT").path("stats");
        assertThat(stats.path("ok").asInt()).isEqualTo(1);
        assertThat(stats.path("lastStatus").asString()).isEqualTo("OK");
    }

    /** docs/servizi/engagement-service.md §7: endpoint che risponde 500 → 3 ritenti pianificati → GAVE_UP; poi *Riprova*. */
    @Test
    void failingEndpointIsRetriedThreeTimesThenGivesUpAndManualRetryWorks() {
        respondWith.set(500);
        JsonNode hook = send("POST", "/v1/webhooks", ADMIN, Map.of("code", "WH-IT-FAIL", "name", "Endpoint rotto",
                "url", hookUrl(), "factTypes", List.of("wallet.points.earned")), 201);
        String secret = hook.path("secret").asString();
        assertThat(status("POST", "/v1/webhooks/WH-IT-FAIL/test", "MARKETING:luca.marketing", null)).isEqualTo(403);

        JsonNode d = send("POST", "/v1/webhooks/WH-IT-FAIL/test", ADMIN, null, 201);
        String id = d.path("id").asString();
        assertThat(d.path("test").asBoolean()).isTrue();
        assertThat(d.path("status").asString()).isEqualTo("FAILED");
        assertThat(d.path("attempt").asInt()).isEqualTo(1);
        assertThat(d.path("maxAttempts").asInt()).isEqualTo(4);
        assertThat(d.path("httpStatus").asInt()).isEqualTo(500);
        assertThat(d.path("error").asString()).isEqualTo("HTTP_ERROR");
        assertThat(d.path("responseExcerpt").asString()).isEqualTo("errore simulato");
        assertNextAfter(d, Duration.ofMinutes(1));
        JsonNode payload = mapper.readTree(d.path("payload").asString());
        assertThat(payload.path("type").asString()).isEqualTo("io.loyaltyhub.fact.wallet.points.earned");
        assertThat(payload.path("lhactor").asString()).isEqualTo(ADMIN);
        assertThat(payload.path("data").path("amount").isNumber()).as("esempio del contratto").isTrue();

        // Non ancora dovuta: nessun tentativo.
        dispatcher.deliverDue(Instant.now());
        assertThat(received(id)).hasSize(1);

        // Secondo e terzo tentativo: 5 e 15 minuti dopo; il job demo sposta il tempo con asOf.
        d = runJobAt(id, d.path("nextAttemptAt").asString());
        assertThat(d.path("attempt").asInt()).isEqualTo(2);
        assertThat(d.path("status").asString()).isEqualTo("FAILED");
        assertNextAfter(d, Duration.ofMinutes(5));
        d = runJobAt(id, d.path("nextAttemptAt").asString());
        assertThat(d.path("attempt").asInt()).isEqualTo(3);
        assertNextAfter(d, Duration.ofMinutes(15));
        d = runJobAt(id, d.path("nextAttemptAt").asString());
        assertThat(d.path("attempt").asInt()).isEqualTo(4);
        assertThat(d.path("status").asString()).isEqualTo("GAVE_UP");
        assertThat(d.path("nextAttemptAt").isMissingNode() || d.path("nextAttemptAt").isNull()).isTrue();

        List<Received> calls = received(id);
        assertThat(calls).as("primo invio + 3 ritenti").hasSize(4);
        String eventId = calls.getFirst().header("X-LH-Event-Id");
        assertThat(calls).allSatisfy(c -> {
            assertThat(c.header("X-LH-Event-Id")).as("stesso evento a ogni ritento").isEqualTo(eventId);
            assertThat(WebhookSignature.verify(secret, c.body(), c.header("X-LH-Signature"))).isTrue();
        });
        // Un altro giro anche molto più avanti non la tocca più.
        send("POST", "/v1/demo/jobs/deliver-webhooks?asOf=" + Instant.now().plus(Duration.ofDays(1)), ADMIN, null, 200);
        assertThat(received(id)).hasSize(4);

        // *Riprova* manuale: solo ADMIN; con l'endpoint tornato su → OK.
        assertThat(status("POST", "/v1/webhook-deliveries/" + id + "/retry", "CARE:paolo.care", null)).isEqualTo(403);
        respondWith.set(200);
        JsonNode retried = send("POST", "/v1/webhook-deliveries/" + id + "/retry", ADMIN, null, 200);
        assertThat(retried.path("status").asString()).isEqualTo("OK");
        assertThat(retried.path("attempt").asInt()).isEqualTo(5);
        assertThat(retried.path("httpStatus").asInt()).isEqualTo(200);
        assertThat(received(id)).hasSize(5);
        assertThat(send("POST", "/v1/webhook-deliveries/" + id + "/retry", ADMIN, null, 409).path("code").asString())
                .isEqualTo("DELIVERY_NOT_RETRYABLE");
        assertThat(status("POST", "/v1/webhook-deliveries/NON-ESISTE/retry", ADMIN, null)).isEqualTo(404);
    }

    @Test
    void updateToggleAndDelete() throws Exception {
        JsonNode created = send("POST", "/v1/webhooks", ADMIN, Map.of("code", "WH-IT-EDIT", "name", "Da modificare",
                "url", hookUrl(), "factTypes", List.of("tier.upgraded")), 201);
        long version = created.path("version").asLong();
        JsonNode off = send("PUT", "/v1/webhooks/WH-IT-EDIT", ADMIN, Map.of("enabled", false, "version", version), 200);
        assertThat(off.path("enabled").asBoolean()).isFalse();
        assertThat(off.path("name").asString()).as("campi assenti invariati").isEqualTo("Da modificare");
        assertThat(send("PUT", "/v1/webhooks/WH-IT-EDIT", ADMIN, Map.of("enabled", true, "version", version), 409)
                .path("code").asString()).isEqualTo("VERSION_CONFLICT");
        assertThat(send("PUT", "/v1/webhooks/WH-IT-EDIT", ADMIN, Map.of("code", "WH-ALTRO"), 409).path("code").asString())
                .isEqualTo("CODE_IMMUTABLE");
        assertThat(status("PUT", "/v1/webhooks/WH-IT-EDIT", "MARKETING:luca.marketing", Map.of("enabled", true))).isEqualTo(403);

        // Spento: un fatto sottoscritto non genera consegne.
        String eventId = "it-wh-off-" + UUID.randomUUID();
        publish(fact(eventId, "io.loyaltyhub.fact.tier.upgraded", "MBR-000003",
                Map.of("previousTier", "SILVER", "newTier", "GOLD", "periodSts", 3020)));
        awaitProcessed(eventId);
        assertThat(get("/v1/webhooks/WH-IT-EDIT/deliveries").path("page").path("totalItems").asInt()).isZero();

        JsonNode renamed = send("PUT", "/v1/webhooks/" + created.path("id").asString(), ADMIN, Map.of("name", "Rinominato",
                "factTypes", List.of("tier.upgraded", "tier.downgraded"), "version", off.path("version").asLong()), 200);
        assertThat(renamed.path("factTypes").size()).isEqualTo(2);
        send("POST", "/v1/webhooks/WH-IT-EDIT/test", ADMIN, null, 201);
        assertThat(status("DELETE", "/v1/webhooks/WH-IT-EDIT", "MARKETING:luca.marketing", null)).isEqualTo(403);
        send("DELETE", "/v1/webhooks/WH-IT-EDIT", ADMIN, null, 204);
        assertThat(status("GET", "/v1/webhooks/WH-IT-EDIT", null, null)).isEqualTo(404);
        assertThat(jdbc.sql("SELECT count(*) FROM webhook_delivery WHERE webhook_id = ?").param(created.path("id").asString())
                .query(Long.class).single()).as("registro eliminato col webhook").isZero();
    }

    // ---------- helper ----------

    /** Stessa verifica fatta con lo script d'esempio di deploy/webhook-receiver, se c'è un node nel PATH. */
    private void verifyWithNodeScript(String secret, Received call) throws Exception {
        Path script = Path.of("../../deploy/webhook-receiver/verify.mjs").toAbsolutePath().normalize();
        boolean nodeAvailable;
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            nodeAvailable = p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            nodeAvailable = false;
        }
        if (!nodeAvailable || !Files.exists(script)) {
            return; // senza node la firma resta verificata in Java (WebhookSignature.verify) qui sopra
        }
        File body = Files.createTempFile("webhook-body", ".json").toFile();
        body.deleteOnExit();
        Files.write(body.toPath(), call.body());
        Process ok = new ProcessBuilder("node", script.toString(), "--secret", secret, "--signature",
                call.header("X-LH-Signature"), "--file", body.getAbsolutePath()).redirectErrorStream(true).start();
        assertThat(ok.waitFor(20, TimeUnit.SECONDS)).isTrue();
        assertThat(ok.exitValue()).as(new String(ok.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isZero();
        Process ko = new ProcessBuilder("node", script.toString(), "--secret", "whsec_sbagliato", "--signature",
                call.header("X-LH-Signature"), "--file", body.getAbsolutePath()).redirectErrorStream(true).start();
        assertThat(ko.waitFor(20, TimeUnit.SECONDS)).isTrue();
        assertThat(ko.exitValue()).isEqualTo(1);
    }

    private JsonNode runJobAt(String deliveryId, String nextAttemptAt) {
        Instant at = Instant.parse(nextAttemptAt).plusSeconds(1);
        JsonNode job = send("POST", "/v1/demo/jobs/deliver-webhooks?asOf=" + at, ADMIN, null, 200);
        assertThat(job.path("attempted").asInt()).isGreaterThanOrEqualTo(1);
        return get("/v1/webhook-deliveries/" + deliveryId);
    }

    private static void assertNextAfter(JsonNode d, Duration delay) {
        Instant last = Instant.parse(d.path("lastAttemptAt").asString());
        assertThat(Instant.parse(d.path("nextAttemptAt").asString())).isEqualTo(last.plus(delay));
    }

    private List<Received> received(String deliveryId) {
        return received.stream().filter(r -> deliveryId.equals(r.header("X-LH-Delivery-Id"))).toList();
    }

    private JsonNode awaitDelivery(String webhook, String eventId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            for (JsonNode d : get("/v1/webhooks/" + webhook + "/deliveries?size=100").path("items")) {
                if (eventId.equals(d.path("eventId").asString())) {
                    return d;
                }
            }
            Thread.sleep(300);
        }
        throw new AssertionError("consegna non creata per " + eventId);
    }

    private void awaitProcessed(String eventId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (jdbc.sql("SELECT count(*) FROM processed_event WHERE event_id = ?").param(eventId).query(Long.class).single() > 0) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("evento non elaborato: " + eventId);
    }

    private List<String> audit(String subject) {
        List<String> out = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "group.id", "webhook-it-" + UUID.randomUUID(), "auto.offset.reset", "earliest",
                "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class))) {
            consumer.subscribe(List.of("lh.audit.v1"));
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline && out.isEmpty()) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(400))) {
                    if (subject.equals(mapper.readTree(r.value()).path("subject").asString())) {
                        out.add(r.value());
                    }
                }
            }
        }
        return out;
    }

    private static Map<String, Object> fact(String id, String type, String memberId, Map<String, Object> data) {
        return Map.of("specversion", "1.0", "id", id, "source", "urn:loyaltyhub:service:wallet", "type", type,
                "subject", "member:" + memberId, "time", Instant.now().toString(), "datacontenttype", "application/json",
                "lhtenant", "aurora", "lhcorrelationid", id, "data", data);
    }

    private void publish(Map<String, Object> event) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class))) {
            String subject = String.valueOf(event.get("subject"));
            producer.send(new ProducerRecord<>("lh.facts.v1", subject.substring("member:".length()),
                    mapper.writeValueAsString(event))).get();
        }
    }

    private static List<String> fields(JsonNode problem) {
        List<String> out = new ArrayList<>();
        problem.path("errors").forEach(e -> out.add(e.path("field").asString()));
        return out;
    }

    private int status(String method, String path, String actor, Object body) {
        var spec = RestClient.create("http://localhost:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (actor != null) spec = spec.header("X-LH-Actor", actor);
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return spec.exchange((req, res) -> res.getStatusCode().value());
    }

    private JsonNode get(String path) {
        return RestClient.create("http://localhost:" + port).get().uri(path).retrieve().body(JsonNode.class);
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
