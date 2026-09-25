package io.loyaltyhub.hub;

import io.loyaltyhub.common.event.LhHeaders;
import io.loyaltyhub.hub.bus.HubInProcessBus;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Consegna "almeno una volta" (docs/04 §5: outbox + consumer idempotenti con {@code processed_event}): nel deployable
 * consolidato si produce traffico reale dalle sole API pubbliche, si aspetta la quiete, si fotografa lo stato di
 * <strong>tutte</strong> le tabelle degli 8 schemi e poi si <strong>riconsegna</strong> ogni messaggio già consegnato
 * (quelli registrati da insight in {@code event_store} sui topic azioni, effetti, fatti e audit) attraverso il bus
 * in-process, con la stessa chiave, lo stesso valore e gli stessi header del relay dell'outbox. Oracolo: la
 * riconsegna non cambia <em>nulla</em> (stessa fotografia, nessun nuovo messaggio a valle, nessuna nuova voce in DLQ).
 *
 * <p>I job di sfondo che non sono consumer (consegna webhook, timeout delle richieste premio, retention di insight)
 * sono spenti: cambierebbero lo stato col passare del tempo, indipendentemente dalla riconsegna.
 */
@SpringBootTest(
        classes = HubApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.config.name=hub",
                "loyaltyhub.webhooks.dispatcher.enabled=false",
                "loyaltyhub.reward.redemption-timeout.enabled=false",
                "loyaltyhub.insight.retention.cron=-"})
@ActiveProfiles({"demo", "inproc"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HubReplayIdempotencyIT {

    private static final EmbeddedPostgres PG = startPg();

    private static final List<String> SCHEMAS =
            List.of("ingestion", "member", "campaign", "wallet", "insight", "reward", "gamification", "engagement");
    /** Topic riconsegnati: tutti tranne la DLQ (i suoi record non sono in event_store, docs Q-111). */
    private static final List<String> REPLAY_TOPICS = List.of("lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1");
    private static final String DLQ_TOPIC = "lh.dlq.v1";
    /** Topic privato del test: nessun servizio lo ascolta; serve da barriera FIFO sul thread di consegna del bus. */
    private static final String BARRIER_TOPIC = "lh.test.replay-barrier";
    /** Finestra di quiete: 6 giri del relay dell'outbox (500 ms) senza alcuna scrittura. */
    private static final long QUIET_WINDOW_MS = 3_000;

    /**
     * Tabelle di business che il traffico deve modificare: la fotografia le copre (tutte le tabelle degli schemi) e
     * il test non passa "a vuoto" se il traffico non le ha toccate. Nomi dalle migrazioni Flyway dei servizi.
     */
    private static final List<String> MUST_CHANGE = List.of(
            "wallet.wallet", "wallet.ledger_entry", "wallet.points_lot", "wallet.member_tier",
            "gamification.play", "gamification.member_badge", "gamification.achievement_progress",
            "gamification.leaderboard_score",
            "reward.redemption", "reward.coupon", "reward.reward",
            "member.member_stats", "member.member_activity_day",
            "engagement.inbox_message",
            "campaign.campaign_counter", "campaign.evaluation_log",
            "insight.audit_entry", "insight.event_store");

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private HubInProcessBus bus;

    private final Map<String, CountDownLatch> barriers = new ConcurrentHashMap<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=ingestion,member,campaign,wallet,insight,reward,gamification,engagement");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    void replayingEveryDeliveredMessageChangesNothing() throws Exception {
        long started = System.nanoTime();
        bus.subscribe(BARRIER_TOPIC, "lh-test-barrier", r -> {
            CountDownLatch latch = barriers.remove(r.key());
            if (latch != null) {
                latch.countDown();
            }
        });

        // 0. Stato di partenza (seed demo), a riposo.
        awaitQuiescence("avvio");
        Map<String, Map<String, JsonNode>> seeded = snapshot();

        // 1. Traffico reale dalle API pubbliche.
        produceTraffic();
        awaitQuiescence("traffico");
        Map<String, Map<String, JsonNode>> before = snapshot();

        List<String> untouched = new ArrayList<>();
        for (String table : MUST_CHANGE) {
            assertThat(before).as("tabella %s nella fotografia", table).containsKey(table);
            if (Objects.equals(seeded.get(table), before.get(table))) {
                untouched.add(table);
            }
        }
        assertThat(untouched).as("tabelle che il traffico doveva modificare (altrimenti il test passa a vuoto)").isEmpty();
        long dlqBefore = count("SELECT count(*) FROM insight.dlq_entry");
        Set<String> dlqEventsBefore = Set.copyOf(
                jdbc.sql("SELECT event_id FROM insight.dlq_entry").query(String.class).list());

        // 2. Riconsegna di ogni messaggio già consegnato, nell'ordine originale (offset globale del bus in-process).
        List<ReplayRow> rows = jdbc.sql("""
                        SELECT e.topic, e.type, e.event_id, e.member_id, e.payload::text AS payload,
                               o.msg_key, o.type AS outbox_type
                        FROM insight.event_store e
                        LEFT JOIN outbox o ON o.topic = e.topic AND o.payload->>'id' = e.event_id
                        WHERE e.topic IN (:topics)
                        ORDER BY e.kafka_offset, e.received_at
                        """)
                .param("topics", REPLAY_TOPICS)
                .query((rs, i) -> new ReplayRow(rs.getString("topic"), rs.getString("type"), rs.getString("event_id"),
                        rs.getString("member_id"), rs.getString("payload"), rs.getString("msg_key"),
                        rs.getString("outbox_type")))
                .list();
        assertThat(rows).as("messaggi da riconsegnare").isNotEmpty();

        Map<String, AtomicInteger> delivered = new ConcurrentHashMap<>();
        for (String topic : REPLAY_TOPICS) {
            delivered.put(topic, new AtomicInteger());
            bus.subscribe(topic, "lh-test-probe", r -> delivered.get(r.topic()).incrementAndGet());
        }
        List<ConsumerRecord<String, String>> dlqDuringReplay = new CopyOnWriteArrayList<>();
        bus.subscribe(DLQ_TOPIC, "lh-test-probe", dlqDuringReplay::add);

        Map<String, Integer> perTopic = new TreeMap<>();
        Map<String, Integer> perType = new TreeMap<>();
        int withOutboxKey = 0;
        for (ReplayRow row : rows) {
            bus.publish(toRecord(row));
            perTopic.merge(row.topic(), 1, Integer::sum);
            perType.merge(row.topic() + "  " + row.type(), 1, Integer::sum);
            if (row.msgKey() != null) {
                withOutboxKey++;
            }
        }
        awaitQuiescence("riconsegna");
        Map<String, Map<String, JsonNode>> after = snapshot();

        // 3. Resoconto.
        StringBuilder report = new StringBuilder("\n=== Riconsegna (HubReplayIdempotencyIT) ===\n");
        report.append("messaggi riconsegnati: ").append(rows.size())
                .append(" (chiave dall'outbox: ").append(withOutboxKey).append(")\n");
        perTopic.forEach((t, n) -> report.append(String.format("  %-16s %5d%n", t, n)));
        report.append("per tipo:\n");
        perType.forEach((t, n) -> report.append(String.format("  %-70s %5d%n", t, n)));
        report.append("tabelle fotografate: ").append(before.size()).append('\n');
        before.forEach((t, r) -> report.append(String.format("  %-42s %6d righe%n", t, r.size())));
        report.append(String.format("durata: %.1f s%n", (System.nanoTime() - started) / 1e9));
        System.out.println(report);

        // 4. Oracolo: nulla è cambiato.
        String diff = diff(before, after);
        if (!diff.isEmpty()) {
            fail("La riconsegna ha cambiato lo stato (consumer non idempotente):\n" + diff);
        }
        for (String topic : REPLAY_TOPICS) {
            assertThat(delivered.get(topic).get())
                    .as("messaggi visti su %s: solo i riconsegnati, nessun nuovo messaggio a valle", topic)
                    .isEqualTo(perTopic.getOrDefault(topic, 0));
        }
        List<String> unexpectedDlq = new ArrayList<>();
        for (ConsumerRecord<String, String> r : dlqDuringReplay) {
            String eventId = mapper.readTree(r.value()).path("id").asString();
            if (!dlqEventsBefore.contains(eventId)) {
                unexpectedDlq.add(eventId + " · " + header(r, LhHeaders.CONSUMER) + " · " + header(r, LhHeaders.ERROR_MESSAGE));
            }
        }
        assertThat(unexpectedDlq).as("nuovi record in DLQ causati dalla riconsegna").isEmpty();
        assertThat(count("SELECT count(*) FROM insight.dlq_entry")).as("voci DLQ").isEqualTo(dlqBefore);
    }

    // ---------- traffico ----------

    private void produceTraffic() {
        for (String scn : List.of("SCN-WEEKEND-ANNA", "SCN-TIER-UP", "SCN-REFERRAL", "SCN-ONBOARDING", "SCN-DIGITAL")) {
            JsonNode run = post("/v1/demo/scenarios/" + scn + "/run", "ADMIN:test", null);
            JsonNode done = awaitRunDone(run.path("runId").asString());
            assertThat(done.path("status").asString()).as("esito di %s", scn).isNotEqualTo("RUNNING");
        }

        // Richiesta premio AUTO_COUPON dal portale: saga richiesta → spesa → conferma → coupon → evasione.
        JsonNode redemption = post("/v1/portal/redemptions", null, Map.of("memberId", "MBR-000004", "rewardCode", "RWD-SHOP-10"));
        String redemptionId = redemption.path("redemptionId").asString();
        await("richiesta " + redemptionId + " FULFILLED", () -> "FULFILLED".equals(
                get("/v1/portal/redemptions/" + redemptionId).path("status").asString()));

        // Giocata su IW-AUTUNNO di Matteo (MBR-000010, giocate nel seed) con un istante piantato: vincita → ponte → punti.
        post("/v1/demo/contests/IW-AUTUNNO/plant-instant", "ADMIN:test", Map.of("prizeCode", "PTS-100"));
        JsonNode play = post("/v1/portal/contests/IW-AUTUNNO/play", null, Map.of("memberId", "MBR-000010"));
        assertThat(play.path("outcome").asString()).isIn("WIN", "LOSE");

        // Rettifica manuale del saldo (ADMIN).
        post("/v1/wallets/MBR-000002/adjustments", "ADMIN:test", Map.of(
                "currency", "PTS", "direction", "CREDIT", "amount", 100, "reason", "GOODWILL",
                "note", "Rettifica di prova per la riconsegna"));
    }

    // ---------- quiete ----------

    /**
     * Quiete: nessuna scrittura su outbox, event_store, processed_event e DLQ per {@link #QUIET_WINDOW_MS}, nessuna riga
     * dell'outbox da pubblicare e il bus ha consegnato tutto ciò che aveva in coda (barriera FIFO).
     */
    private void awaitQuiescence(String phase) {
        long deadline = System.currentTimeMillis() + 90_000;
        List<Long> previous = counters();
        while (System.currentTimeMillis() < deadline) {
            sleep(QUIET_WINDOW_MS);
            barrier();
            List<Long> current = counters();
            if (current.equals(previous) && current.get(2) == 0) {
                return;
            }
            previous = current;
        }
        throw new AssertionError("Sistema non a riposo entro 90 s (" + phase + "): " + previous);
    }

    /** event_store, outbox (totale), outbox da pubblicare, processed_event, dlq_entry. */
    private List<Long> counters() {
        return List.of(
                count("SELECT count(*) FROM insight.event_store"),
                count("SELECT count(*) FROM outbox"),
                count("SELECT count(*) FROM outbox WHERE published_at IS NULL"),
                count("SELECT count(*) FROM processed_event"),
                count("SELECT count(*) FROM insight.dlq_entry"));
    }

    /** Il bus consegna su un solo thread FIFO: quando il record-barriera arriva, tutto ciò che lo precede è consegnato. */
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

    // ---------- riconsegna ----------

    /** Stesso record che produce {@code OutboxRelay}: chiave dell'outbox, payload, header {@code lh-*} e content-type. */
    private ProducerRecord<String, String> toRecord(ReplayRow row) {
        JsonNode env = mapper.readTree(row.payload());
        String key = row.msgKey();
        if (key == null) {
            key = row.memberId() != null ? row.memberId() : env.path("subject").asString(null);
        }
        RecordHeaders headers = new RecordHeaders();
        headers.add(new RecordHeader(LhHeaders.TYPE, bytes(row.outboxType() != null ? row.outboxType() : row.type())));
        addHeader(headers, LhHeaders.CORRELATION_ID, env.get("lhcorrelationid"));
        addHeader(headers, LhHeaders.CAUSATION_ID, env.get("lhcausationid"));
        addHeader(headers, LhHeaders.HOP, env.get("lhhop"));
        addHeader(headers, LhHeaders.ACTOR, env.get("lhactor"));
        headers.add(new RecordHeader("content-type", bytes(LhHeaders.CONTENT_TYPE_VALUE)));
        return new ProducerRecord<>(row.topic(), null, key, row.payload(), headers);
    }

    private static void addHeader(RecordHeaders headers, String name, JsonNode value) {
        if (value != null && !value.isNull()) {
            headers.add(new RecordHeader(name, bytes(value.asString())));
        }
    }

    private record ReplayRow(String topic, String type, String eventId, String memberId, String payload,
                             String msgKey, String outboxType) {
    }

    // ---------- fotografia ----------

    /**
     * Tutte le righe di tutte le tabelle degli 8 schemi (tranne lo storico Flyway), per tabella e chiave primaria.
     * Le colonne non sono scritte a mano: ogni riga è {@code to_jsonb(t)} e la chiave viene dal catalogo.
     */
    private Map<String, Map<String, JsonNode>> snapshot() {
        Map<String, Map<String, JsonNode>> snap = new TreeMap<>();
        List<String[]> tables = jdbc.sql("""
                        SELECT table_schema, table_name FROM information_schema.tables
                        WHERE table_schema IN (:schemas) AND table_type = 'BASE TABLE'
                          AND table_name <> 'flyway_schema_history'
                        ORDER BY table_schema, table_name
                        """)
                .param("schemas", SCHEMAS)
                .query((rs, i) -> new String[]{rs.getString(1), rs.getString(2)})
                .list();
        for (String[] t : tables) {
            List<String> pk = jdbc.sql("""
                            SELECT k.column_name
                            FROM information_schema.table_constraints c
                            JOIN information_schema.key_column_usage k
                              ON k.constraint_name = c.constraint_name AND k.table_schema = c.table_schema
                             AND k.table_name = c.table_name
                            WHERE c.constraint_type = 'PRIMARY KEY' AND c.table_schema = ? AND c.table_name = ?
                            ORDER BY k.ordinal_position
                            """)
                    .params(t[0], t[1])
                    .query(String.class)
                    .list();
            List<String> json = jdbc.sql("SELECT to_jsonb(t)::text FROM \"" + t[0] + "\".\"" + t[1] + "\" t")
                    .query(String.class)
                    .list();
            Map<String, JsonNode> rows = new TreeMap<>();
            Map<String, Integer> seen = new HashMap<>();
            for (String j : json) {
                JsonNode row = mapper.readTree(j);
                String key;
                if (pk.isEmpty()) {
                    key = j + " #" + seen.merge(j, 1, Integer::sum); // senza chiave: multiinsieme di righe
                } else {
                    List<String> parts = new ArrayList<>();
                    for (String col : pk) {
                        parts.add(row.path(col).asString());
                    }
                    key = String.join(" | ", parts);
                }
                rows.put(key, row);
            }
            snap.put(t[0] + "." + t[1], rows);
        }
        return snap;
    }

    /** Differenze leggibili: tabella, chiave, colonna prima → dopo (al più 20 righe per tabella). */
    private static String diff(Map<String, Map<String, JsonNode>> before, Map<String, Map<String, JsonNode>> after) {
        StringBuilder out = new StringBuilder();
        // Prima le tabelle di business, poi la contabilità del consumo (processed_event, outbox).
        List<String> tables = new ArrayList<>(new java.util.TreeSet<>(before.keySet()));
        after.keySet().stream().filter(t -> !tables.contains(t)).forEach(tables::add);
        tables.sort(java.util.Comparator.comparing(
                (String t) -> t.endsWith(".processed_event") || t.endsWith(".outbox")).thenComparing(t -> t));
        for (String table : tables) {
            Map<String, JsonNode> b = before.getOrDefault(table, Map.of());
            Map<String, JsonNode> a = after.getOrDefault(table, Map.of());
            if (b.equals(a)) {
                continue;
            }
            List<String> lines = new ArrayList<>();
            Set<String> keys = new java.util.TreeSet<>(b.keySet());
            keys.addAll(a.keySet());
            for (String key : keys) {
                JsonNode rb = b.get(key);
                JsonNode ra = a.get(key);
                if (Objects.equals(rb, ra)) {
                    continue;
                }
                if (rb == null) {
                    lines.add("  + [" + cut(key) + "] " + cut(ra.toString()));
                } else if (ra == null) {
                    lines.add("  - [" + cut(key) + "] " + cut(rb.toString()));
                } else {
                    Set<String> cols = new java.util.TreeSet<>(rb.propertyNames());
                    cols.addAll(ra.propertyNames());
                    for (String col : cols) {
                        if (!Objects.equals(rb.get(col), ra.get(col))) {
                            lines.add("  ~ [" + cut(key) + "] " + col + ": " + cut(String.valueOf(rb.get(col)))
                                    + " -> " + cut(String.valueOf(ra.get(col))));
                        }
                    }
                }
            }
            out.append(table).append(" (").append(b.size()).append(" -> ").append(a.size()).append(" righe)\n");
            lines.stream().limit(20).forEach(l -> out.append(l).append('\n'));
            if (lines.size() > 20) {
                out.append("  … altre ").append(lines.size() - 20).append(" differenze\n");
            }
        }
        return out.toString();
    }

    private static String cut(String s) {
        return s.length() <= 160 ? s : s.substring(0, 157) + "…";
    }

    // ---------- helper ----------

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private JsonNode awaitRunDone(String runId) {
        JsonNode[] run = new JsonNode[1];
        await("fine della run " + runId, () -> {
            run[0] = get("/v1/demo/scenario-runs/" + runId);
            return !"RUNNING".equals(run[0].path("status").asString());
        });
        return run[0];
    }

    private void await(String what, java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(250);
        }
        throw new AssertionError("Timeout in attesa di: " + what);
    }

    private JsonNode post(String uri, String actor, Object body) {
        var req = client().post().uri(uri).contentType(MediaType.APPLICATION_JSON);
        if (actor != null) {
            req.header("X-LH-Actor", actor);
        }
        if (body != null) {
            req.body(body);
        }
        return req.retrieve().body(JsonNode.class);
    }

    private JsonNode get(String uri) {
        return client().get().uri(uri).retrieve().body(JsonNode.class);
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
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
