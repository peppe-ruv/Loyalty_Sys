package io.loyaltyhub.ingestion.testbook;

import io.loyaltyhub.ingestion.IngestionApplication;
import io.loyaltyhub.ingestion.domain.Source;
import io.loyaltyhub.ingestion.infra.MemberIndexRepository;
import io.loyaltyhub.ingestion.infra.SourceRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Banco comune del testbook TB-ING (docs/testbook/TB-ING-ingresso.md, docs/16 §1bis): un solo contesto Spring per
 * tutte le classi {@code TestbookIng*IT} (stessa configurazione ⇒ contesto in cache), Postgres in-process (Zonky) ed
 * EmbeddedKafka come gli altri IT del modulo. Orologio del servizio sostituito da un orologio di prova regolabile
 * ({@link TbClock}): istante fisso per i casi temporali, altrimenti l'ora reale. Ogni caso usa id freschi (eventi,
 * membri, fonti, tipi): nessuna dipendenza dallo stato demo modificato da altri casi.
 */
@SpringBootTest(classes = {IngestionApplication.class, TestbookIngHarness.TbClockConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@EmbeddedKafka(partitions = 1, topics = {"lh.facts.v1", "lh.actions.v1", "lh.audit.v1", "lh.dlq.v1", "lh.effects.v1"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class TestbookIngHarness {

    static final String ACTIONS = "lh.actions.v1";
    static final String AUDIT = "lh.audit.v1";
    static final String URN = "urn:loyaltyhub:source:";
    static final ZoneId ROME = ZoneId.of("Europe/Rome");

    /** Istante di caricamento del banco, prima dell'avvio del contesto (e quindi del seed demo). */
    static final Instant STARTED = Instant.now();
    private static final EmbeddedPostgres PG = startPg();
    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 40_000));
    static final TbClock CLOCK = new TbClock();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=ingestion");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    /** L'orologio di prova sostituisce quello di lh-common ({@code @ConditionalOnMissingBean}). */
    @TestConfiguration
    static class TbClockConfig {
        @Bean
        Clock clock() {
            return CLOCK;
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired MemberIndexRepository members;
    @Autowired SourceRepository sources;

    @AfterEach
    void realTime() {
        CLOCK.set(null);
    }

    /** Un caso per riga del CSV {@code testbook/ing/<file>}; l'orologio torna reale dopo ogni riga. */
    Stream<DynamicTest> rows(String file, TestbookIngRows.RowTest body) {
        return TestbookIngRows.of(file, body, () -> CLOCK.set(null));
    }

    // ---------- orologio ----------

    /** Orologio regolabile: {@code fixed == null} ⇒ ora reale. */
    static final class TbClock extends Clock {
        private volatile Instant fixed;

        void set(Instant instant) {
            this.fixed = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            Instant f = fixed;
            return f != null ? f : Instant.now();
        }
    }

    // ---------- id freschi ----------

    static int seq() {
        return SEQ.incrementAndGet();
    }

    static String freshEventId() {
        return "tb-" + UUID.randomUUID();
    }

    /** Membro nuovo nell'indice ({@code status} null ⇒ non indicizzato); externalId ed e-mail propri. */
    Fresh freshMember(String status) {
        int n = seq();
        String id = String.format("MBR-6%05d", n);
        String external = "TB-EXT-" + n;
        String email = "socio.tb" + n + "@example.org";
        if (status != null) {
            members.upsert(id, external, email, status);
        }
        return new Fresh(id, external, email);
    }

    record Fresh(String memberId, String externalId, String email) {
    }

    /** Fonte nuova ({@code allowed} vuoto = tutti i tipi). */
    String freshSource(boolean enabled, List<String> allowed) {
        String code = "tbsrc" + seq();
        sources.upsert(new Source(code, "Fonte testbook " + code, "HTTP", enabled, allowed, null));
        return code;
    }

    void setSource(String code, boolean enabled, List<String> allowed) {
        sources.upsert(new Source(code, "Fonte testbook " + code, "HTTP", enabled, allowed, null));
    }

    // ---------- HTTP ----------

    record Response(int status, JsonNode body) {
        String text(String field) {
            JsonNode n = body.path(field);
            return n.isMissingNode() || n.isNull() ? null : n.asString();
        }
    }

    Response call(String method, String path, String actor, Object body) {
        RestClient.RequestBodySpec spec = RestClient.create("http://localhost:" + port)
                .method(HttpMethod.valueOf(method)).uri(path);
        if (actor != null) {
            spec.header("X-LH-Actor", actor);
        }
        if (body instanceof String raw) {
            spec.header("Content-Type", "application/json").body(raw.getBytes(StandardCharsets.UTF_8));
        } else if (body != null) {
            spec.header("Content-Type", "application/json").body(mapper.writeValueAsBytes(body));
        }
        return spec.exchange((req, res) -> {
            byte[] bytes = res.getBody().readAllBytes();
            JsonNode node;
            try {
                node = bytes.length == 0 ? mapper.getNodeFactory().nullNode() : mapper.readTree(bytes);
            } catch (RuntimeException e) {
                node = mapper.getNodeFactory().textNode(new String(bytes, StandardCharsets.UTF_8));
            }
            return new Response(res.getStatusCode().value(), node);
        });
    }

    Response postEvent(Object event) {
        return call("POST", "/v1/events", null, event);
    }

    /** CloudEvent d'ingresso minimo e valido ({@code purchase.completed}). */
    ObjectNode event(String id, String source, String type, String subject, Instant time, JsonNode data) {
        ObjectNode e = mapper.createObjectNode();
        e.put("specversion", "1.0");
        e.put("id", id);
        e.put("source", source.startsWith("urn:") ? source : URN + source);
        e.put("type", type);
        e.put("subject", subject);
        e.put("time", time.toString());
        e.set("data", data);
        return e;
    }

    JsonNode purchaseData(String orderId, Object amount) {
        ObjectNode d = mapper.createObjectNode();
        d.put("orderId", orderId);
        d.set("amount", mapper.valueToTree(amount));
        d.put("currency", "EUR");
        d.put("channel", "ONLINE");
        return d;
    }

    JsonNode json(String text) {
        return mapper.readTree(text);
    }

    // ---------- osservazioni sul DB ----------

    /** Pubblicazioni su {@code lh.actions.v1} (righe outbox) per (fonte, id): il conteggio è esatto e sincrono. */
    long publications(String sourceCode, String eventId) {
        return jdbc.sql("""
                        SELECT count(*) FROM outbox WHERE topic = ? AND payload->>'id' = ?
                          AND payload->>'source' = ?
                        """)
                .params(ACTIONS, eventId, URN + sourceCode).query(Long.class).single();
    }

    /** Pubblicazioni su {@code lh.actions.v1} per id (gli id dei casi sono unici). */
    long publicationsById(String eventId) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE topic = ? AND payload->>'id' = ?")
                .params(ACTIONS, eventId).query(Long.class).single();
    }

    /** Ultima riga del monitor per id evento. */
    Map<String, Object> lastRow(String eventId) {
        return jdbc.sql("""
                        SELECT id, status, reject_code, member_id, origin, subject, reject_detail, source_code,
                               correlation_id, resolution, resolved_by
                        FROM inbound_event WHERE event_id = ? ORDER BY received_at DESC, id DESC LIMIT 1
                        """)
                .param(eventId).query().singleRow();
    }

    /**
     * Esito atteso di un ingresso: 202, {@code status}/{@code rejectCode} nella risposta e sull'ultima riga del monitor;
     * una pubblicazione solo se {@code ACCEPTED} (per {@code DUPLICATE} la verifica il chiamante).
     */
    void assertOutcome(Response r, String eventId, String status, String code) {
        assertThat(r.status()).as("HTTP (corpo: %s)", r.body()).isEqualTo(202);
        assertThat(r.text("status")).as("status (corpo: %s)", r.body()).isEqualTo(status);
        assertThat(r.text("rejectCode")).as("rejectCode (corpo: %s)", r.body()).isEqualTo(nullIfDash(code));
        Map<String, Object> row = lastRow(eventId);
        assertThat(row.get("status")).as("status della riga").isEqualTo(status);
        assertThat(row.get("reject_code")).as("reject_code della riga").isEqualTo(nullIfDash(code));
        if (!"DUPLICATE".equals(status)) {
            assertThat(publicationsById(eventId)).as("pubblicazioni su lh.actions.v1")
                    .isEqualTo("ACCEPTED".equals(status) ? 1 : 0);
        }
    }

    /** L'envelope pubblicato (ultimo) per (fonte, id). */
    JsonNode published(String sourceCode, String eventId) {
        String payload = jdbc.sql("""
                        SELECT payload::text FROM outbox WHERE topic = ? AND payload->>'id' = ? AND payload->>'source' = ?
                        ORDER BY created_at DESC LIMIT 1
                        """)
                .params(ACTIONS, eventId, URN + sourceCode).query(String.class).single();
        return mapper.readTree(payload);
    }

    String publishedKey(String sourceCode, String eventId) {
        return jdbc.sql("""
                        SELECT msg_key FROM outbox WHERE topic = ? AND payload->>'id' = ? AND payload->>'source' = ?
                        ORDER BY created_at DESC LIMIT 1
                        """)
                .params(ACTIONS, eventId, URN + sourceCode).query(String.class).single();
    }

    long rows(String sourceCode, String eventId) {
        return jdbc.sql("SELECT count(*) FROM inbound_event WHERE source_code = ? AND event_id = ?")
                .params(sourceCode, eventId).query(Long.class).single();
    }

    long rowsByEventId(String eventId) {
        return jdbc.sql("SELECT count(*) FROM inbound_event WHERE event_id = ?").param(eventId).query(Long.class).single();
    }

    /** Id interno dell'ultima riga per (fonte, id). */
    String rowId(String sourceCode, String eventId) {
        return jdbc.sql("""
                        SELECT id FROM inbound_event WHERE source_code = ? AND event_id = ?
                        ORDER BY received_at DESC, id DESC LIMIT 1
                        """)
                .params(sourceCode, eventId).query(String.class).single();
    }

    String column(String rowId, String column) {
        return jdbc.sql("SELECT " + column + "::text FROM inbound_event WHERE id = ?").param(rowId)
                .query(String.class).optional().orElse(null);
    }

    /** Voci di audit accodate per l'entità ({@code entityType:entityId}). */
    List<JsonNode> audits(String key) {
        return jdbc.sql("SELECT payload::text FROM outbox WHERE topic = ? AND msg_key = ? ORDER BY created_at")
                .params(AUDIT, key).query(String.class).list().stream().map(mapper::readTree).toList();
    }

    static void waitFor(BooleanSupplier condition, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertThat(condition.getAsBoolean()).as("condizione attesa entro " + timeout).isTrue();
    }

    static String nullIfDash(String s) {
        return s == null || s.equals("-") ? null : s;
    }

    private static EmbeddedPostgres startPg() {
        try {
            EmbeddedPostgres pg = EmbeddedPostgres.builder().start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    pg.close();
                } catch (Exception ignored) {
                    // arresto della JVM
                }
            }));
            return pg;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
