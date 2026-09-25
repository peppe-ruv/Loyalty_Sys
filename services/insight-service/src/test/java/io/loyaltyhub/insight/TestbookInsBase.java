package io.loyaltyhub.insight;

import com.sun.net.httpserver.HttpServer;
import io.loyaltyhub.insight.domain.DlqEntry;
import io.loyaltyhub.insight.infra.DlqRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Contesto condiviso dei test d'integrazione TB-INS su insight-service (Dlq, Trace, Kpi, Audit, Events): stesse
 * annotazioni e stessa sorgente di proprietà ⇒ un solo contesto Spring in cache per tutte le classi che la estendono
 * (EmbeddedKafka + Postgres Zonky, profilo {@code demo}). L'orologio dell'applicazione è {@link TestbookClock} (ogni
 * caso lo imposta e lo rilascia). Ingestion è uno stub HTTP che risponde come sceglie il caso (riprocessa DLQ, ADR-002
 * eccezione 1). Il job di retention è spento (si prova in {@code TestbookInsStoreIT}, con un contesto proprio).
 * Ogni caso usa identificativi propri: nessuna dipendenza dallo stato mutabile di altri casi.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {InsightApplication.class, TestbookInsBase.ClockConfig.class},
        properties = "loyaltyhub.insight.retention.cron=-")
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class TestbookInsBase extends TestbookInsSupport {

    static final EmbeddedPostgres PG = startPg();
    static final TestbookClock CLOCK = new TestbookClock();

    /** Risposta dello stub di ingestion: stato HTTP, corpo; {@code drop} = connessione chiusa senza risposta. */
    record StubReply(int status, String body, boolean drop) {
    }

    /** Richiesta ricevuta dallo stub. */
    record StubCall(String method, String path, String actor, String reprocess, JsonNode body) {
    }

    static final BlockingQueue<StubCall> INGESTION_CALLS = new LinkedBlockingQueue<>();
    static final AtomicReference<StubReply> INGESTION_REPLY = new AtomicReference<>(accepted());
    static final HttpServer INGESTION = startIngestionStub();

    @Autowired
    protected DlqRepository dlqRepo;

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        @Primary
        Clock testbookClock() {
            return CLOCK;
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=insight");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "10");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
        registry.add("loyaltyhub.insight.ingestion-url",
                () -> "http://localhost:" + INGESTION.getAddress().getPort() + "/");
    }

    @BeforeEach
    void resetStub() {
        INGESTION_CALLS.clear();
        INGESTION_REPLY.set(accepted());
    }

    @AfterEach
    void releaseClock() {
        CLOCK.reset();
    }

    static StubReply accepted() {
        return new StubReply(202, "{\"status\":\"ACCEPTED\"}", false);
    }

    // ---------- voci DLQ ----------

    /** Tipo d'origine di esempio per famiglia (docs/05 §3–§6); {@code UNKNOWN} = senza tipo riconoscibile. */
    static String typeOf(String family) {
        return switch (family) {
            case "ACTION" -> ACTION + "app.login.daily";
            case "EFFECT" -> EFFECT + "points.grant";
            case "FACT" -> FACT + "tier.upgraded";
            case "AUDIT" -> AUDIT_TYPE;
            default -> null;
        };
    }

    static String topicOfFamily(String family) {
        return switch (family) {
            case "ACTION" -> "lh.actions.v1";
            case "EFFECT" -> "lh.effects.v1";
            case "FACT" -> "lh.facts.v1";
            case "AUDIT" -> "lh.audit.v1";
            default -> null;
        };
    }

    /** Voce DLQ aperta scritta direttamente (lo stato di partenza del caso), con l'envelope originale come payload. */
    DlqEntry openEntry(String family, String consumer, String errorCode, String correlationId, Instant seenAt) {
        String eventId = uid("EVT-DLQ");
        String type = typeOf(family);
        ObjectNode payload = envelope(eventId, type == null ? "sconosciuto" : type,
                "ACTION".equals(family) ? "urn:loyaltyhub:source:app" : "urn:loyaltyhub:service:campaign",
                "member:MBR-000002", correlationId, null, Instant.parse("2026-09-15T10:00:00Z"),
                Map.of("platform", "ANDROID"));
        if (type == null) {
            payload.remove("type");
        }
        DlqEntry e = new DlqEntry(io.loyaltyhub.common.ids.Ulid.next(Clock.systemUTC()), eventId, topicOfFamily(family),
                type, family, consumer, errorCode, "x.NonRetryableEventException", "errore di prova",
                "x.NonRetryableEventException: errore di prova\n\tat io.loyaltyhub.Test.run(Test.java:1)", false, 1,
                "MBR-000002", correlationId, payload, seenAt == null ? Instant.now() : seenAt,
                DlqEntry.OPEN, null, null, null);
        if (!dlqRepo.insert(e, 0, 0L)) {
            throw new IllegalStateException("voce DLQ non inserita");
        }
        return e;
    }

    String statusOf(String dlqId) {
        return dlqRepo.findById(dlqId).map(DlqEntry::status).orElse("NONE");
    }

    // ---------- stub di ingestion ----------

    private static HttpServer startIngestionStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();
            server.createContext("/", exchange -> {
                byte[] in = exchange.getRequestBody().readAllBytes();
                JsonNode body;
                try {
                    body = json.readTree(new String(in, StandardCharsets.UTF_8));
                } catch (RuntimeException e) {
                    body = json.createObjectNode();
                }
                INGESTION_CALLS.add(new StubCall(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("X-LH-Actor"),
                        exchange.getRequestHeaders().getFirst("X-LH-Reprocess"), body));
                StubReply reply = INGESTION_REPLY.get();
                if (reply.drop()) {
                    exchange.close();
                    return;
                }
                byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply.status(), out.length == 0 ? -1 : out.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                } catch (IOException ignored) {
                    // client già chiuso
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
