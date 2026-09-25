package io.loyaltyhub.hub;

import io.loyaltyhub.common.event.LhHeaders;
import io.loyaltyhub.hub.bus.HubInProcessBus;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.DynamicTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base comune del testbook TB-E2E (docs/testbook/TB-E2E-percorsi.md): percorsi reali tra servizi nel deployable
 * consolidato (ADR-023) col bus in-process (ADR-024, profilo {@code inproc}). Non contiene casi: le classi concrete
 * {@code TestbookE2e*IT} avviano ciascuna il proprio contesto Spring con il proprio Postgres incorporato e un orologio
 * fisso che avanza (niente orologio di parete: le date del seed sono relative a quell'«oggi»).
 *
 * <p>Strumenti: chiamate HTTP alle API pubbliche; membri nuovi a ogni caso (nessuna dipendenza dallo stato mutabile del
 * seed, salvo i percorsi dei personaggi che partono da un reset); quiete del sistema (outbox vuoto, bus in-process
 * consegnato fino a una barriera FIFO, event store fermo) invece di attese fisse; catena di eventi di un tracciato letta
 * dall'event store di insight; riconsegna di un messaggio già consegnato (stessi chiave, valore e header del relay
 * dell'outbox); servizio «addormentato» (riga del wallet bloccata: il consumer resta fermo sul messaggio); guasto di un
 * handler per N tentativi (trigger SQL di prova).
 */
abstract class TestbookE2eSupportIT {

    static final String ADMIN = "ADMIN:marta.admin";
    static final String CARE = "CARE:paolo.care";
    static final String MARKETING = "MARKETING:luca.marketing";
    static final ZoneId ROME = ZoneId.of("Europe/Rome");
    static final String INTERNAL_SOURCE = "urn:loyaltyhub:source:internal";

    /** Tipi non contati nelle catene: {@code achievement.progressed} è emesso «al cambio di valore» (vedi il documento). */
    static final Set<String> NOT_COUNTED = Set.of("FACT:achievement.progressed");

    private static final String BARRIER_TOPIC = "lh.test.e2e-barrier";
    private static final AtomicLong SEQ = new AtomicLong();

    final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    HubInProcessBus bus;

    @Autowired
    Clock clock;

    private final Map<String, CountDownLatch> barriers = new ConcurrentHashMap<>();
    private final AtomicBoolean barrierReady = new AtomicBoolean();

    // ---------- avvio ----------

    static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void datasource(DynamicPropertyRegistry registry, EmbeddedPostgres pg) {
        String base = pg.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url",
                () -> base + "&currentSchema=ingestion,member,campaign,wallet,insight,reward,gamification,engagement");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    /** Orologio che parte da {@code target} (UTC) e avanza col tempo reale: «oggi» fisso per il seed e i casi. */
    static Clock startingAt(String target) {
        return Clock.offset(Clock.systemUTC(), Duration.between(Instant.now(), Instant.parse(target)));
    }

    // ---------- righe del testbook come casi JUnit ----------

    /** Una riga di un CSV del testbook, letta per nome di colonna. */
    record Row(String id, String desc, Map<String, String> cols, int line) {
        String get(String column) {
            String v = cols.get(column);
            if (v == null) {
                throw new IllegalArgumentException("colonna assente: " + column + " in " + id);
            }
            return v;
        }

        long num(String column) {
            return Long.parseLong(get(column).trim());
        }

        boolean is(String column, String value) {
            return value.equals(get(column));
        }

        boolean blank(String column) {
            String v = cols.get(column);
            return v == null || v.isBlank() || v.equals("-");
        }
    }

    /** Un caso per riga di {@code /testbook/e2e/<file>}: nome «[ID] descrizione», sorgente = riga del CSV. */
    static Stream<DynamicTest> rows(String file, Consumer<Row> body) {
        String resource = "/testbook/e2e/" + file;
        return read(resource).stream().map(r -> DynamicTest.dynamicTest("[" + r.id() + "] " + r.desc(),
                URI.create("classpath:" + resource + "?line=" + r.line()), () -> body.accept(r)));
    }

    static List<Row> read(String resource) {
        try (InputStream in = TestbookE2eSupportIT.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("risorsa assente: " + resource);
            }
            List<List<String>> records = parseCsv(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            List<String> header = records.get(0);
            List<Row> out = new ArrayList<>();
            for (int i = 1; i < records.size(); i++) {
                List<String> rec = records.get(i);
                if (rec.size() == 1 && rec.get(0).isEmpty()) {
                    continue;
                }
                Map<String, String> cols = new LinkedHashMap<>();
                for (int c = 0; c < header.size(); c++) {
                    cols.put(header.get(c), c < rec.size() ? rec.get(c) : "");
                }
                out.add(new Row(rec.get(0), rec.get(1), cols, i + 1));
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** CSV RFC 4180 (virgolette doppie, virgole e virgolette raddoppiate nei campi). */
    private static List<List<String>> parseCsv(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                current.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n') {
                current.add(field.toString());
                field.setLength(0);
                records.add(current);
                current = new ArrayList<>();
            } else if (ch != '\r') {
                field.append(ch);
            }
        }
        if (field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            records.add(current);
        }
        return records;
    }

    // ---------- HTTP ----------

    record Resp(int status, JsonNode body) {
        String code() {
            return body.path("code").asString("");
        }
    }

    Resp send(String method, String path, String actor, Object body) {
        var spec = RestClient.create("http://localhost:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (actor != null) {
            spec = spec.header("X-LH-Actor", actor);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode node = text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text);
            return new Resp(res.getStatusCode().value(), node);
        });
    }

    JsonNode get(String path) {
        Resp r = send("GET", path, ADMIN, null);
        assertThat(r.status()).as("GET " + path + " → " + r.body()).isEqualTo(200);
        return r.body();
    }

    JsonNode ok(Resp r, int... expected) {
        assertThat(Arrays.stream(expected).anyMatch(s -> s == r.status())).as("stato HTTP " + r.status() + " " + r.body()).isTrue();
        return r.body();
    }

    // ---------- membri ----------

    record Member(String id, String email, String firstName, String referralCode) {
        String subject() {
            return "member:" + id;
        }
    }

    static String uniqueTag() {
        return Long.toString(System.nanoTime(), 36) + SEQ.incrementAndGet();
    }

    /** Nome di fantasia univoco fatto di sole lettere (cercabile senza falsi positivi). */
    static String uniqueName() {
        String digits = Long.toString(System.nanoTime() % 1_000_000_000L) + SEQ.incrementAndGet();
        StringBuilder sb = new StringBuilder("Tb");
        for (char c : digits.toCharArray()) {
            sb.append((char) ('a' + (c - '0')));
        }
        return sb.toString();
    }

    /** Nuovo membro dal portale (F-MBR-06), con codice amico facoltativo; attende la fine della catena di benvenuto. */
    Member newMember(String referralCode) {
        String first = uniqueName();
        String email = "tb.e2e." + uniqueTag() + "@example.org";
        Resp r = register(first, email, referralCode);
        assertThat(r.status()).as("iscrizione " + r.body()).isEqualTo(201);
        quiet();
        return new Member(r.body().path("id").asString(), email, first, r.body().path("referralCode").asString(null));
    }

    Member newMember() {
        return newMember(null);
    }

    Resp register(String firstName, String email, String referralCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("firstName", firstName);
        body.put("lastName", "Prova");
        body.put("email", email);
        body.put("channel", "PORTAL");
        if (referralCode != null) {
            body.put("referralCode", referralCode);
        }
        return send("POST", "/v1/members", null, body);
    }

    /** Rettifica di credito {@code GOODWILL} (ruolo CARE): porta il saldo al valore voluto per la saga. */
    void credit(String memberId, long amount) {
        ok(send("POST", "/v1/wallets/" + memberId + "/adjustments", CARE, Map.of("currency", "PTS", "direction", "CREDIT",
                "amount", amount, "reason", "GOODWILL", "note", "Preparazione del caso di testbook E2E")), 200, 201);
        quiet();
    }

    // ---------- azioni ----------

    /** Invia un CloudEvent a {@code POST /v1/events}; restituisce la risposta (202 + esito). */
    Resp postEvent(String id, String source, String type, String subject, Instant time, Map<String, Object> data) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("specversion", "1.0");
        event.put("id", id);
        event.put("source", source.startsWith("urn:") ? source : "urn:loyaltyhub:source:" + source);
        event.put("type", type);
        event.put("subject", subject);
        event.put("time", time.toString());
        event.put("data", data);
        return send("POST", "/v1/events", null, event);
    }

    /** Azione accettata: restituisce il suo {@code correlationId} (letto dal monitor ingressi). */
    String act(String source, String type, String subject, Instant time, Map<String, Object> data) {
        String id = "tb-e2e-" + uniqueTag();
        Resp r = postEvent(id, source, type, subject, time, data);
        assertThat(r.status()).as("POST /v1/events " + r.body()).isEqualTo(202);
        assertThat(r.body().path("status").asString()).as("esito dell'ingresso " + r.body()).isEqualTo("ACCEPTED");
        return correlationOf(id);
    }

    String correlationOf(String eventId) {
        return jdbc.sql("SELECT correlation_id FROM ingestion.inbound_event WHERE event_id = ? AND status = 'ACCEPTED'")
                .param(eventId).query(String.class).single();
    }

    String purchase(String subject, double amount, Instant time) {
        return act("ecommerce", "purchase.completed", subject, time, Map.of(
                "orderId", "ORD-TB-" + uniqueTag(), "amount", amount, "currency", "EUR", "channel", "ONLINE"));
    }

    /** Un istante del giorno feriale di «oggi» (giovedì nel seed dei casi): {@code hh:mm} di Roma, oggi. */
    Instant todayAt(int hour, int minute) {
        return LocalDateTime.of(LocalDateTime.ofInstant(clock.instant(), ROME).toLocalDate(),
                java.time.LocalTime.of(hour, minute)).atZone(ROME).toInstant();
    }

    static Instant rome(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(ROME).toInstant();
    }

    // ---------- scenari (docs/10 §8) ----------

    JsonNode runScenario(String code) {
        Resp started = send("POST", "/v1/demo/scenarios/" + code + "/run", ADMIN, null);
        assertThat(started.status()).as("avvio " + code + " " + started.body()).isIn(200, 202);
        String runId = started.body().path("runId").asString();
        JsonNode[] run = new JsonNode[1];
        await("fine della run " + code, 60_000, () -> {
            run[0] = get("/v1/demo/scenario-runs/" + runId);
            return !"RUNNING".equals(run[0].path("status").asString());
        });
        quiet();
        return run[0];
    }

    void resetDemo() {
        quiet();
        Resp r = send("POST", "/v1/demo/reset", ADMIN, null);
        assertThat(r.status()).as("reset " + r.body()).isEqualTo(200);
        quiet();
    }

    // ---------- attese ----------

    void await(String what, long timeoutMs, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            pause(100);
        }
        throw new AssertionError("Condizione non raggiunta entro " + timeoutMs + " ms: " + what);
    }

    /**
     * Quiete: il bus in-process ha consegnato tutto ciò che aveva in coda (barriera FIFO), l'outbox non ha righe da
     * pubblicare e l'event store non è cambiato tra due barriere consecutive.
     */
    void quiet() {
        ensureBarrier();
        long deadline = System.currentTimeMillis() + 90_000;
        List<Long> previous = List.of();
        while (System.currentTimeMillis() < deadline) {
            barrier();
            long unpublished = count("SELECT count(*) FROM outbox WHERE published_at IS NULL");
            List<Long> current = List.of(count("SELECT count(*) FROM outbox"), count("SELECT count(*) FROM insight.event_store"),
                    count("SELECT count(*) FROM insight.dlq_entry"), count("SELECT count(*) FROM processed_event"));
            if (unpublished == 0 && current.equals(previous)) {
                return;
            }
            previous = current;
            pause(unpublished == 0 ? 120 : 60);
        }
        throw new AssertionError("Sistema non a riposo entro 90 s");
    }

    private void ensureBarrier() {
        if (barrierReady.compareAndSet(false, true)) {
            bus.subscribe(BARRIER_TOPIC, "lh-test-e2e-barrier", r -> {
                CountDownLatch latch = barriers.remove(r.key());
                if (latch != null) {
                    latch.countDown();
                }
            });
        }
    }

    private void barrier() {
        String id = UUID.randomUUID().toString();
        CountDownLatch latch = new CountDownLatch(1);
        barriers.put(id, latch);
        bus.publish(new ProducerRecord<>(BARRIER_TOPIC, id, "{}"));
        try {
            assertThat(latch.await(60, TimeUnit.SECONDS)).as("barriera del bus in-process").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    long count(String sql, Object... params) {
        var spec = jdbc.sql(sql);
        for (Object p : params) {
            spec = spec.param(p);
        }
        return spec.query(Long.class).single();
    }

    // ---------- catena di eventi di un tracciato (event store di insight) ----------

    record Ev(String id, String family, String shortType, String source, String memberId, String causation,
              Integer hop, JsonNode payload) {
        String key() {
            return family + ":" + shortType;
        }

        JsonNode data() {
            return payload.path("data");
        }
    }

    /** Eventi con quel {@code correlationId}, in ordine di consegna (audit escluso: non fa parte della catena). */
    List<Ev> chain(String correlationId) {
        return jdbc.sql("""
                        SELECT event_id, family, short_type, source, member_id, causation_id, hop, payload::text AS payload
                        FROM insight.event_store WHERE correlation_id = ? AND family <> 'AUDIT' ORDER BY kafka_offset
                        """)
                .param(correlationId)
                .query((rs, i) -> new Ev(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), (Integer) rs.getObject(7), mapper.readTree(rs.getString(8))))
                .list();
    }

    /** {@code "A:purchase.completed=1 F:campaign.evaluated=3 E:points.grant=3"} → mappa FAMIGLIA:tipo → numero. */
    static Map<String, Integer> parseChain(String spec) {
        Map<String, Integer> out = new TreeMap<>();
        for (String token : spec.trim().split("\\s+")) {
            if (token.isBlank() || token.equals("-")) {
                continue;
            }
            String[] kv = token.split("=");
            String[] fk = kv[0].split(":", 2);
            String family = switch (fk[0]) {
                case "A" -> "ACTION";
                case "E" -> "EFFECT";
                case "F" -> "FACT";
                default -> fk[0];
            };
            out.merge(family + ":" + fk[1], Integer.parseInt(kv[1]), Integer::sum);
        }
        return out;
    }

    static Map<String, Integer> counts(List<Ev> events) {
        Map<String, Integer> out = new TreeMap<>();
        for (Ev e : events) {
            if (!NOT_COUNTED.contains(e.key())) {
                out.merge(e.key(), 1, Integer::sum);
            }
        }
        return out;
    }

    /** Catena esatta (tutti i tipi tranne {@link #NOT_COUNTED}) e forma dell'albero: una radice, ponte a hop+1. */
    List<Ev> assertChain(String correlationId, String spec) {
        List<Ev> events = chain(correlationId);
        assertThat(counts(events)).as("catena di eventi del tracciato %s", correlationId).isEqualTo(parseChain(spec));
        assertTree(events);
        return events;
    }

    /** Solo i tipi elencati (per i casi in cui la specifica non fissa il resto della catena). */
    List<Ev> assertChainIncludes(String correlationId, String spec) {
        List<Ev> events = chain(correlationId);
        Map<String, Integer> actual = counts(events);
        parseChain(spec).forEach((k, n) -> assertThat(actual.getOrDefault(k, 0)).as("%s nel tracciato %s: %s", k,
                correlationId, actual).isEqualTo(n));
        assertTree(events);
        return events;
    }

    /**
     * Forma del tracciato (docs/05 §2, §7): tutti gli eventi nello stesso albero (una sola radice senza causa, ogni altra
     * causa è un evento del tracciato); le azioni del ponte hanno fonte {@code internal} e {@code lhhop} = hop del fatto
     * che le causa + 1; nessun hop oltre 3.
     */
    void assertTree(List<Ev> events) {
        assertThat(events).as("tracciato vuoto").isNotEmpty();
        Map<String, Ev> byId = new LinkedHashMap<>();
        events.forEach(e -> byId.put(e.id(), e));
        long roots = events.stream().filter(e -> e.causation() == null).count();
        assertThat(roots).as("un solo albero: radici %s", events.stream().filter(e -> e.causation() == null)
                .map(Ev::key).toList()).isEqualTo(1);
        for (Ev e : events) {
            if (e.causation() != null) {
                assertThat(byId).as("causa di %s (%s) nel tracciato", e.key(), e.causation()).containsKey(e.causation());
            }
            assertThat(e.hop() == null ? 0 : e.hop()).as("lhhop di %s", e.key()).isBetween(0, 3);
            if (e.family().equals("ACTION") && e.hop() != null && e.hop() > 0) {
                Ev parent = byId.get(e.causation());
                assertThat(e.source()).as("fonte dell'azione del ponte %s", e.key()).isEqualTo(INTERNAL_SOURCE);
                assertThat(parent.family()).as("il ponte parte da un fatto").isEqualTo("FACT");
                assertThat(e.hop()).as("lhhop del ponte").isEqualTo((parent.hop() == null ? 0 : parent.hop()) + 1);
            }
        }
    }

    /** Il tracciato di insight (BO-25) è un solo albero e non è fallito. */
    JsonNode assertTrace(String correlationId, String expectedStatus) {
        JsonNode trace = get("/v1/traces/" + correlationId);
        long roots = 0;
        for (JsonNode n : trace.path("nodes")) {
            if ((n.path("parentEventId").isNull() || n.path("parentEventId").isMissingNode())
                    && !"AUDIT".equalsIgnoreCase(n.path("family").asString())) {
                roots++;
            }
        }
        assertThat(roots).as("radici del tracciato %s", correlationId).isEqualTo(1);
        if ("FAILED".equals(expectedStatus)) {
            assertThat(trace.path("status").asString()).isEqualTo("FAILED");
        } else {
            assertThat(trace.path("status").asString()).as("stato del tracciato").isNotEqualTo("FAILED");
        }
        return trace;
    }

    static List<Ev> ofKey(List<Ev> events, String key) {
        return events.stream().filter(e -> e.key().equals(key)).toList();
    }

    /** Correlazione della radice di un fatto (es. {@code member.registered}) del membro. */
    String rootCorrelation(String memberId, String family, String shortType) {
        return jdbc.sql("""
                        SELECT correlation_id FROM insight.event_store
                        WHERE member_id = ? AND family = ? AND short_type = ? ORDER BY kafka_offset LIMIT 1
                        """)
                .params(memberId, family, shortType).query(String.class).single();
    }

    // ---------- stato per servizio ----------

    long pts(String memberId) {
        return get("/v1/portal/wallets/" + memberId).path("balances").path("PTS").path("active").asLong();
    }

    long sts(String memberId) {
        return get("/v1/portal/wallets/" + memberId).path("tier").path("periodSts").asLong();
    }

    String tier(String memberId) {
        return get("/v1/portal/wallets/" + memberId).path("tier").path("code").asString();
    }

    /** Movimenti del libro mastro nati da un tracciato: {@code TIPO:VALUTA:importo[:campagna]} ordinati. */
    List<String> ledger(String memberId, String correlationId) {
        return jdbc.sql("""
                        SELECT type || ':' || currency || ':' || amount || coalesce(':' || campaign_code, '')
                        FROM wallet.ledger_entry WHERE member_id = ? AND correlation_id = ?
                        """)
                .params(memberId, correlationId).query(String.class).list().stream().sorted().toList();
    }

    List<String> ledgerAll(String memberId) {
        return jdbc.sql("""
                        SELECT type || ':' || currency || ':' || amount || coalesce(':' || campaign_code, '')
                        FROM wallet.ledger_entry WHERE member_id = ?
                        """)
                .param(memberId).query(String.class).list().stream().sorted().toList();
    }

    /** Template dei messaggi in inbox nati da un tracciato, ordinati. */
    List<String> inbox(String correlationId) {
        return jdbc.sql("SELECT template_code FROM engagement.inbox_message WHERE correlation_id = ?")
                .param(correlationId).query(String.class).list().stream().sorted().toList();
    }

    List<String> inboxOf(String memberId) {
        return jdbc.sql("SELECT template_code FROM engagement.inbox_message WHERE member_id = ?")
                .param(memberId).query(String.class).list().stream().sorted().toList();
    }

    List<String> badges(String memberId) {
        return jdbc.sql("SELECT badge_code FROM gamification.member_badge WHERE member_id = ?")
                .param(memberId).query(String.class).list().stream().sorted().toList();
    }

    /** Stock residuo del premio ({@code null} = illimitato). */
    Integer stock(String rewardCode) {
        return jdbc.sql("SELECT stock_remaining FROM reward.reward WHERE code = ?").param(rewardCode).query(Integer.class)
                .optional().orElse(null);
    }

    /** {@code skipped[]} di un {@code campaign.evaluated}: codice campagna → motivo. */
    static Map<String, String> skipped(Ev evaluated) {
        Map<String, String> out = new java.util.HashMap<>();
        for (JsonNode s : evaluated.data().path("skipped")) {
            out.put(s.path("campaignCode").asString(), s.path("reason").asString());
        }
        return out;
    }

    static List<String> matched(Ev evaluated) {
        List<String> out = new ArrayList<>();
        for (JsonNode s : evaluated.data().path("matched")) {
            out.add(s.path("campaignCode").asString());
        }
        return out;
    }

    // ---------- gioco ----------

    Resp play(String memberId) {
        return send("POST", "/v1/portal/contests/IW-AUTUNNO/play", null, Map.of("memberId", memberId));
    }

    String delivery(String playId) {
        return jdbc.sql("SELECT delivery_status FROM gamification.play WHERE id = ?").param(playId).query(String.class).single();
    }

    /**
     * Precondizione dei casi di gioco con membri nuovi: nessun istante già maturo (o che maturerebbe durante il caso)
     * oltre a quelli piantati: gli istanti {@code OPEN} di IW-AUTUNNO entro domani vanno a +30 giorni (restano nel
     * periodo del concorso).
     */
    void onlyPlantedInstants() {
        Instant now = clock.instant();
        jdbc.sql("""
                        UPDATE gamification.winning_instant SET instant_at = ?
                        WHERE contest_id = (SELECT id FROM gamification.contest WHERE code = 'IW-AUTUNNO')
                          AND status = 'OPEN' AND instant_at <= ?
                        """)
                .params(java.sql.Timestamp.from(now.plus(Duration.ofDays(30))), java.sql.Timestamp.from(now.plus(Duration.ofDays(1))))
                .update();
    }

    /** Pianta un istante del premio e gioca (vincita garantita, US-E06-09); restituisce la risposta della giocata. */
    JsonNode plantAndPlay(String memberId, String prizeCode) {
        ok(send("POST", "/v1/demo/contests/IW-AUTUNNO/plant-instant", ADMIN, Map.of("prizeCode", prizeCode)), 200, 201);
        Resp r = play(memberId);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.body();
    }

    // ---------- premi ----------

    Resp redeem(String memberId, String rewardCode, boolean shipping) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("memberId", memberId);
        body.put("rewardCode", rewardCode);
        if (shipping) {
            body.put("shipping", Map.of("name", "Destinatario Prova", "street", "Via dei Test 1", "zip", "00100", "city", "Roma"));
        }
        return send("POST", "/v1/portal/redemptions", null, body);
    }

    JsonNode redemption(String id) {
        return get("/v1/portal/redemptions/" + id);
    }

    String redemptionStatus(String id) {
        return redemption(id).path("status").asString();
    }

    static List<String> sorted(String spaceSeparated) {
        if (spaceSeparated == null || spaceSeparated.isBlank() || spaceSeparated.equals("-")) {
            return List.of();
        }
        return Arrays.stream(spaceSeparated.trim().split("\\s+")).sorted().toList();
    }

    /**
     * Fotografia dello stato di un membro in tutti gli schemi (righe {@code to_jsonb}, ordinate): serve agli oracoli
     * «la riconsegna non cambia nulla».
     */
    Map<String, List<String>> memberState(String... memberIds) {
        Map<String, String> tables = new LinkedHashMap<>();
        tables.put("wallet.wallet", "member_id");
        tables.put("wallet.ledger_entry", "member_id");
        tables.put("wallet.points_lot", "member_id");
        tables.put("wallet.member_tier", "member_id");
        tables.put("wallet.tier_history", "member_id");
        tables.put("campaign.campaign_counter", "member_id");
        tables.put("campaign.evaluation_log", "member_id");
        tables.put("campaign.member_snapshot", "member_id");
        tables.put("member.member", "id");
        tables.put("member.member_projection", "member_id");
        tables.put("member.member_stats", "member_id");
        tables.put("gamification.member_badge", "member_id");
        tables.put("gamification.achievement_progress", "member_id");
        tables.put("gamification.leaderboard_score", "member_id");
        tables.put("gamification.play", "member_id");
        tables.put("gamification.play_grant", "member_id");
        tables.put("gamification.gamification_member_snapshot", "member_id");
        tables.put("reward.redemption", "member_id");
        tables.put("reward.coupon", "member_id");
        tables.put("reward.reward_member_snapshot", "member_id");
        tables.put("engagement.inbox_message", "member_id");
        tables.put("engagement.engagement_member_snapshot", "member_id");
        tables.put("ingestion.inbound_event", "member_id");
        tables.put("ingestion.member_index", "member_id");
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (var t : tables.entrySet()) {
            List<String> rows = new ArrayList<>();
            for (String id : memberIds) {
                rows.addAll(jdbc.sql("SELECT to_jsonb(t)::text FROM " + t.getKey() + " t WHERE " + t.getValue() + " = ?")
                        .param(id).query(String.class).list());
            }
            rows.sort(String::compareTo);
            out.put(t.getKey(), rows);
        }
        out.put("reward.reward(stock)", jdbc.sql("SELECT code || ':' || coalesce(stock_remaining, -1) FROM reward.reward ORDER BY code")
                .query(String.class).list());
        return out;
    }

    // ---------- riconsegna (docs/04 §5: consegna almeno una volta, consumer idempotenti) ----------

    /** Riconsegna un messaggio già consegnato con la stessa chiave, lo stesso valore e gli stessi header del relay. */
    void redeliver(String eventId) {
        redeliver(eventId, null);
    }

    /** Come {@link #redeliver(String)}; con {@code newId} ≠ null lo stesso contenuto con un nuovo id CloudEvents. */
    void redeliver(String eventId, String newId) {
        var row = jdbc.sql("""
                        SELECT e.topic, e.type, e.member_id, e.payload::text AS payload, o.msg_key, o.type AS outbox_type
                        FROM insight.event_store e
                        LEFT JOIN outbox o ON o.topic = e.topic AND o.payload->>'id' = e.event_id
                        WHERE e.event_id = ?
                        """)
                .param(eventId)
                .query((rs, i) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6)})
                .single();
        JsonNode env = mapper.readTree(row[3]);
        String payload = row[3];
        if (newId != null) {
            ObjectNode copy = (ObjectNode) env.deepCopy();
            copy.put("id", newId);
            payload = copy.toString();
        }
        String key = row[4] != null ? row[4] : row[2];
        RecordHeaders headers = new RecordHeaders();
        headers.add(new RecordHeader(LhHeaders.TYPE, bytes(row[5] != null ? row[5] : row[1])));
        header(headers, LhHeaders.CORRELATION_ID, env.get("lhcorrelationid"));
        header(headers, LhHeaders.CAUSATION_ID, env.get("lhcausationid"));
        header(headers, LhHeaders.HOP, env.get("lhhop"));
        header(headers, LhHeaders.ACTOR, env.get("lhactor"));
        headers.add(new RecordHeader("content-type", bytes(LhHeaders.CONTENT_TYPE_VALUE)));
        bus.publish(new ProducerRecord<>(row[0], null, key, payload, headers));
    }

    private static void header(RecordHeaders headers, String name, JsonNode value) {
        if (value != null && !value.isNull()) {
            headers.add(new RecordHeader(name, bytes(value.asString())));
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ---------- servizio addormentato e guasti ----------

    /**
     * Wallet «addormentato» per un membro: la riga del suo wallet PTS resta bloccata da un'altra transazione, quindi il
     * consumer del wallet si ferma sul primo messaggio del membro (col bus in-process la consegna FIFO resta ferma come
     * un consumer spento con l'arretrato in coda). {@link AutoCloseable#close()} = risveglio.
     */
    AutoCloseable walletAsleep(String memberId) {
        try {
            Connection c = dataSource.getConnection();
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM wallet.wallet WHERE member_id = ? AND currency = 'PTS' FOR UPDATE")) {
                ps.setString(1, memberId);
                ps.executeQuery().close();
            }
            return () -> {
                c.rollback();
                c.setAutoCommit(true);
                c.close();
            };
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Guasto di prova: le prossime {@code times} insert su {@code table} (filtrate da {@code when}, SQL su {@code NEW})
     * falliscono con un'eccezione ritentabile. La sequenza non è transazionale, quindi conta anche i tentativi annullati.
     */
    String failInserts(String table, String when, int times) {
        String tag = "tb_fail_" + uniqueTag().toLowerCase(Locale.ROOT);
        jdbc.sql("CREATE SEQUENCE public." + tag).update();
        jdbc.sql("""
                CREATE FUNCTION public.%s() RETURNS trigger LANGUAGE plpgsql AS $f$
                BEGIN
                  IF (%s) AND nextval('public.%s') <= %d THEN
                    RAISE EXCEPTION 'guasto simulato dal testbook E2E';
                  END IF;
                  RETURN NEW;
                END $f$
                """.formatted(tag, when, tag, times)).update();
        jdbc.sql("CREATE TRIGGER " + tag + " BEFORE INSERT ON " + table + " FOR EACH ROW EXECUTE FUNCTION public." + tag + "()")
                .update();
        return tag + "@" + table;
    }

    void dropFailure(String handle) {
        String[] p = handle.split("@");
        jdbc.sql("DROP TRIGGER IF EXISTS " + p[0] + " ON " + p[1]).update();
        jdbc.sql("DROP FUNCTION IF EXISTS public." + p[0] + "()").update();
        jdbc.sql("DROP SEQUENCE IF EXISTS public." + p[0]).update();
    }

    // ---------- insiemi ----------

    static Set<String> setOf(String... values) {
        return new HashSet<>(List.of(values));
    }
}
