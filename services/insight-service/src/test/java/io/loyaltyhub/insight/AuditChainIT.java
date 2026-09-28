package io.loyaltyhub.insight;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.loyaltyhub.insight.application.AuditAnchorJob;
import io.loyaltyhub.insight.application.AuditAnchorLog;
import io.loyaltyhub.insight.application.AuditRetentionGuard;
import io.loyaltyhub.insight.domain.AuditAnchor;
import io.loyaltyhub.insight.domain.AuditChainHead;
import io.loyaltyhub.insight.domain.AuditHashChain;
import io.loyaltyhub.insight.domain.AuditRecord;
import io.loyaltyhub.insight.infra.AuditChainRepository;
import io.loyaltyhub.insight.infra.AuditRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Audit a catena di hash (F2-GRC-07 parte 1, ADR-043, ADR-044, ADR-038, docs/18 §3.15 punto 5, M8.12a): catena per
 * servizio calcolata all'inserimento, verifica ({@code GET /v1/audit/verify}, anche contro un'ancora copiata dai log),
 * scritture ammesse e rifiutate dal database nella fase expand, prove REDACT delle anonimizzazioni, retention che lascia
 * la catena verificabile e non scende sotto l'età minima, ancoraggio giornaliero anche nei log, accodamenti concorrenti
 * senza buchi. Ogni caso usa servizi propri (il database è condiviso dal contesto).
 * <p>
 * Due tipi di alterazione: «con le credenziali dell'applicazione» (SQL normale, i trigger restano attivi, i flag si
 * possono alzare a mano) e «da chi amministra il database» (trigger spenti dentro una transazione: nessun'altra sessione
 * vede le tabelle senza protezione).
 */
class AuditChainIT extends TestbookInsBase {

    private static final Instant T = Instant.parse("2026-09-20T10:00:00.250001Z");
    private static final Pattern ANCHOR_LINE =
            Pattern.compile("audit-anchor service=(\\S+) seq=(\\d+) entryHash=([0-9a-f]{64}) kind=(\\w+) anchoredAt=(\\S+)");

    @Autowired
    private AuditRepository audits;

    @Autowired
    private AuditChainRepository chain;

    @Autowired
    private AuditAnchorJob anchorJob;

    @Autowired
    private PlatformTransactionManager txManager;

    private final ObjectMapper json = new ObjectMapper();

    // ---------- supporto ----------

    private AuditRecord record(String service, int i, Instant at) {
        ObjectNode before = json.createObjectNode().put("priority", 100 + i).put("nome", "Campagna «" + i + "»");
        ObjectNode after = json.createObjectNode().put("priority", 90 + i).put("budget", 1.50);
        return new AuditRecord(uid("AUD"), uid("EVT-CHN"), at, "ADMIN", "marta.admin", service, "CAMPAIGN",
                "CMP-" + i, "UPDATE", "Modificata campagna " + i, before, after, uid("COR"));
    }

    /** {@code n} voci accodate a {@code service} (come l'ingest: dal repository, catena dal trigger). */
    private void append(String service, int n) {
        for (int i = 1; i <= n; i++) {
            assertThat(audits.insert(record(service, i, T.plusSeconds(i)))).isTrue();
        }
    }

    private Resp verifyCall(String query, String actor) {
        return call("GET", "/v1/audit/verify" + (query == null ? "" : "?" + query), actor, null);
    }

    /** Esito della catena di {@code service} da {@code GET /v1/audit/verify} (ADMIN). */
    private JsonNode report(String service) {
        return report("service=" + service, service);
    }

    /** Esito della catena di {@code service} confrontata con l'ancora {@code seq}/{@code hash}. */
    private JsonNode reportWith(String service, long seq, String hash) {
        return report("service=" + service + "&seq=" + seq + "&hash=" + hash, service);
    }

    private JsonNode report(String query, String service) {
        Resp r = verifyCall(query, actor("ADMIN"));
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        assertThat(r.body().path("services").size()).isEqualTo(1);
        JsonNode report = r.body().path("services").get(0);
        assertThat(report.path("service").asString()).isEqualTo(service);
        assertThat(r.body().path("status").asString()).isEqualTo(report.path("status").asString());
        return report;
    }

    private void assertOk(JsonNode report) {
        assertThat(report.path("status").asString()).as(report.toString()).isEqualTo("OK");
        assertThat(report.has("brokenSeq")).as(report.toString()).isFalse();
    }

    private void assertBroken(JsonNode report, long seq, String reason) {
        assertThat(report.path("status").asString()).as(report.toString()).isEqualTo("BROKEN");
        assertThat(report.path("brokenSeq").asLong()).as(report.toString()).isEqualTo(seq);
        assertThat(report.path("reason").asString()).as(report.toString()).isEqualTo(reason);
    }

    private List<Long> seqs(String service) {
        return jdbc.sql("SELECT seq FROM audit_entry WHERE service = ? ORDER BY seq").param(service)
                .query(Long.class).list();
    }

    private long headSeq(String service) {
        return jdbc.sql("SELECT seq FROM audit_chain_head WHERE service = ?").param(service).query(Long.class).single();
    }

    private String entryHash(String service, long seq) {
        return jdbc.sql("SELECT entry_hash FROM audit_entry WHERE service = ? AND seq = ?").params(service, seq)
                .query(String.class).single();
    }

    private String entryId(String service, long seq) {
        return jdbc.sql("SELECT id FROM audit_entry WHERE service = ? AND seq = ?").params(service, seq)
                .query(String.class).single();
    }

    private List<String> anchorKinds(String service) {
        return jdbc.sql("SELECT kind || ':' || seq FROM audit_anchor WHERE service = ? ORDER BY id").param(service)
                .query(String.class).list();
    }

    /** Prove REDACT della catena audit.redaction per la voce {@code entryId}: {@code memberId|seq|contentHash}. */
    private List<String> redactions(String entryId) {
        return jdbc.sql("""
                        SELECT coalesce(after ->> 'memberId', '-') || '|' || (after ->> 'seq') || '|' || (after ->> 'contentHash')
                        FROM audit_entry WHERE service = 'audit.redaction' AND action = 'REDACT' AND entity_id = ? ORDER BY seq
                        """).param(entryId).query(String.class).list();
    }

    /** Chi amministra il database: trigger delle tre tabelle spenti solo dentro questa transazione. */
    private void asDatabaseAdmin(Runnable statements) {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            for (String table : List.of("audit_entry", "audit_chain_head", "audit_anchor")) {
                jdbc.sql("ALTER TABLE " + table + " DISABLE TRIGGER USER").update();
            }
            statements.run();
            for (String table : List.of("audit_entry", "audit_chain_head", "audit_anchor")) {
                jdbc.sql("ALTER TABLE " + table + " ENABLE TRIGGER USER").update();
            }
        });
    }

    /** Chi ha le credenziali dell'applicazione: SQL normale in una transazione, trigger attivi. */
    private void asApplication(Runnable statements) {
        new TransactionTemplate(txManager).executeWithoutResult(s -> statements.run());
    }

    /** Il fatto che porta {@code memberId} in ANONYMIZED, come insight lo registra nell'event store; il suo id. */
    private String anonymizationFact(String memberId) {
        return anonymizationFact(memberId,
                "{\"data\": {\"memberId\": \"" + memberId + "\", \"status\": \"ANONYMIZED\"}}");
    }

    /** Come {@link #anonymizationFact(String)}, con il payload registrato (per esempio troncato a 8 KB). */
    private String anonymizationFact(String memberId, String payload) {
        String eventId = uid("EVT-ANON");
        jdbc.sql("""
                        INSERT INTO event_store (event_id, topic, family, type, short_type, source, member_id,
                                                 kafka_partition, kafka_offset, payload)
                        VALUES (?, 'lh.facts.v1', 'FACT', 'io.loyaltyhub.fact.member.updated', 'member.updated',
                                'urn:loyaltyhub:service:member', ?, 0, 0, cast(? AS jsonb))
                        """)
                .params(eventId, memberId, payload)
                .update();
        return eventId;
    }

    private static String sqlState(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException e) {
                return e.getSQLState();
            }
        }
        return null;
    }

    private void assertRejected(Runnable write) {
        Throwable t = catchThrowable(write::run);
        assertThat(t).as("scrittura rifiutata").isNotNull();
        assertThat(sqlState(t)).as(String.valueOf(t)).isEqualTo("42501");
    }

    /** Righe {@code audit-anchor} scritte sul logger dedicato mentre {@code action} gira. */
    private List<ILoggingEvent> anchorLogDuring(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(AuditAnchorLog.LOGGER);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
    }

    private static Matcher anchorLine(List<ILoggingEvent> lines, String service) {
        return lines.stream().map(e -> ANCHOR_LINE.matcher(e.getFormattedMessage()))
                .filter(m -> m.matches() && m.group(1).equals(service)).findFirst()
                .orElseThrow(() -> new AssertionError("nessuna riga audit-anchor per " + service));
    }

    // ---------- catena all'ingest ----------

    @Test
    @DisplayName("ingest da lh.audit.v1: voci in catena contigua dalla genesi, duplicato senza nuovo anello, verifica OK")
    void chainOnIngest() {
        String service = uid("chn-ingest").toLowerCase();
        List<ObjectNode> events = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("service", service);
            data.put("entityType", "CAMPAIGN");
            data.put("entityId", "CMP-" + i);
            data.put("action", "UPDATE");
            data.put("summary", "Modificata campagna «Estate " + i + "» → priorità " + (100 - i));
            data.put("before", Map.of("priority", 100, "name", "Estate", "tags", List.of("a", "b")));
            data.put("after", Map.of("priority", 100 - i, "budget", 12.5));
            ObjectNode env = envelope(uid("EVT-CHN"), AUDIT_TYPE, "urn:loyaltyhub:service:campaign",
                    "CAMPAIGN:CMP-" + i, uid("COR"), null, T.plusSeconds(i), data);
            env.put("lhactor", "MARKETING:luca.marketing");
            events.add(env);
        }
        publish("lh.audit.v1", events.get(0));
        publish("lh.audit.v1", events.get(1));
        publish("lh.audit.v1", events.get(1)); // riconsegna: nessun anello in più
        publish("lh.audit.v1", events.get(2));
        await("tre voci in catena", () -> seqs(service).size() == 3, 20_000);

        assertThat(seqs(service)).containsExactly(1L, 2L, 3L);
        List<String[]> links = jdbc.sql("SELECT prev_hash, entry_hash FROM audit_entry WHERE service = ? ORDER BY seq")
                .param(service).query((rs, n) -> new String[]{rs.getString(1), rs.getString(2)}).list();
        assertThat(links.get(0)[0]).isEqualTo("0".repeat(64));
        assertThat(links.get(1)[0]).isEqualTo(links.get(0)[1]);
        assertThat(links.get(2)[0]).isEqualTo(links.get(1)[1]);
        assertThat(headSeq(service)).isEqualTo(3);

        JsonNode report = report(service);
        assertOk(report);
        assertThat(report.path("checked").asLong()).isEqualTo(3);
        assertThat(report.path("firstSeq").asLong()).isEqualTo(1);
        assertThat(report.path("headSeq").asLong()).isEqualTo(3);
    }

    @Test
    @DisplayName("valori di catena proposti da chi inserisce: ignorati, il database li calcola")
    void chainValuesAreComputedByTheDatabase() {
        String service = uid("chn-forge").toLowerCase();
        append(service, 2);
        jdbc.sql("""
                        INSERT INTO audit_entry (id, event_id, at, actor_role, actor_name, service, entity_type,
                                                 entity_id, action, summary, seq, prev_hash, content_hash, entry_hash,
                                                 redacted_at)
                        VALUES (?, ?, now(), 'ADMIN', 'marta.admin', ?, 'CAMPAIGN', 'CMP-9', 'UPDATE', 'x',
                                999, 'falso', 'falso', 'falso', now())
                        """)
                .params(uid("AUD"), uid("EVT"), service).update();
        assertThat(seqs(service)).containsExactly(1L, 2L, 3L);
        assertThat(jdbc.sql("SELECT redacted_at IS NULL FROM audit_entry WHERE service = ? AND seq = 3").param(service)
                .query(Boolean.class).single()).isTrue();
        assertOk(report(service));
    }

    @Test
    @DisplayName("forma canonica: resa jsonb di PostgreSQL e hash SQL uguali al vettore Java")
    void jsonbRenderingMatchesTheCanonicalForm() {
        assertThat(jdbc.sql("SELECT cast('{\"aa\": 2, \"b\": 1}' AS jsonb)::text").query(String.class).single())
                .isEqualTo("{\"b\": 1, \"aa\": 2}");
        assertThat(jdbc.sql("SELECT cast('{\"x\":1.50}' AS jsonb)::text").query(String.class).single())
                .isEqualTo("{\"x\": 1.50}");
        String sql = jdbc.sql("SELECT audit_content_hash(NULL, cast('{\"aa\":2,\"b\":1}' AS jsonb), cast('{\"x\":1.50}' AS jsonb))")
                .query(String.class).single();
        assertThat(sql).isEqualTo(AuditHashChain.contentHash(null, "{\"b\": 1, \"aa\": 2}", "{\"x\": 1.50}"))
                .isEqualTo("e9162b8a2548c34c384eba5423b2d17e8547c4dfcacb76d229e7610bcbb0e37e");
        assertThat(jdbc.sql("SELECT audit_min_retention_days()").query(Integer.class).single())
                .as("stessa età minima nel database e nel controllo all'avvio").isEqualTo(AuditRetentionGuard.MIN_DAYS);
    }

    // ---------- verifica ----------

    @Test
    @DisplayName("verifica: solo ADMIN (SPEC-GAP Q-399); 404 per un servizio sconosciuto; 400 per un'ancora incompleta")
    void verifyAccess() {
        String service = uid("chn-access").toLowerCase();
        append(service, 1);
        for (String who : new String[]{"MARKETING", "CARE", "LEGAL", "ANALYST", "NONE"}) {
            Resp r = verifyCall("service=" + service, actor(who));
            assertThat(r.status()).as(who + " " + r.text()).isEqualTo(403);
        }
        assertThat(verifyCall("service=chn-sconosciuto", actor("ADMIN")).status()).isEqualTo(404);
        for (String bad : new String[]{"seq=1&hash=" + "a".repeat(64), "service=" + service + "&seq=1",
                "service=" + service + "&hash=" + "a".repeat(64), "service=" + service + "&seq=0&hash=" + "a".repeat(64),
                "service=" + service + "&seq=1&hash=xyz"}) {
            assertThat(verifyCall(bad, actor("ADMIN")).status()).as(bad).isEqualTo(400);
        }
        Resp all = verifyCall(null, actor("ADMIN"));
        assertThat(all.status()).isEqualTo(200);
        assertThat(all.body().path("verifiedAt").asString()).isNotBlank();
        List<String> services = new ArrayList<>();
        all.body().path("services").forEach(s -> services.add(s.path("service").asString()));
        assertThat(services).contains(service).isSorted();
    }

    static Stream<Arguments> tampering() {
        return Stream.of(
                Arguments.of("sintesi", "UPDATE audit_entry SET summary = 'Nessuna modifica' WHERE service = ? AND seq = 3",
                        3, "CONTENT_ALTERED"),
                Arguments.of("stato dopo (jsonb)", "UPDATE audit_entry SET after = '{\"priority\": 1}' WHERE service = ? AND seq = 3",
                        3, "CONTENT_ALTERED"),
                Arguments.of("sintesi marcata come anonimizzata, senza prova",
                        "UPDATE audit_entry SET summary = 'Membro anonimo', redacted_at = now() WHERE service = ? AND seq = 3",
                        3, "REDACTION_UNRECORDED"),
                Arguments.of("attore", "UPDATE audit_entry SET actor_name = 'mallory' WHERE service = ? AND seq = 3",
                        3, "ENTRY_ALTERED"),
                Arguments.of("istante", "UPDATE audit_entry SET at = at + interval '1 second' WHERE service = ? AND seq = 3",
                        3, "ENTRY_ALTERED"),
                Arguments.of("oggetto", "UPDATE audit_entry SET entity_id = 'CMP-X' WHERE service = ? AND seq = 3",
                        3, "ENTRY_ALTERED"),
                Arguments.of("voce in mezzo rimossa", "DELETE FROM audit_entry WHERE service = ? AND seq = 3",
                        3, "MISSING_ENTRY"),
                Arguments.of("prima voce rimossa senza ancora", "DELETE FROM audit_entry WHERE service = ? AND seq = 1",
                        2, "UNANCHORED_START"),
                Arguments.of("ultima voce rimossa", "DELETE FROM audit_entry WHERE service = ? AND seq = 5",
                        5, "TAIL_MISSING"),
                Arguments.of("testa riscritta", "UPDATE audit_chain_head SET entry_hash = repeat('b', 64) WHERE service = ?",
                        5, "HEAD_MISMATCH"));
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("alterazione da chi amministra il database: la verifica indica la voce esatta e il motivo")
    @MethodSource("tampering")
    void tamperingIsLocated(String what, String sql, long brokenSeq, String reason) {
        String service = uid("chn-tamper").toLowerCase();
        append(service, 5);
        assertOk(report(service));
        asDatabaseAdmin(() -> assertThat(jdbc.sql(sql).param(service).update()).isEqualTo(1));
        assertBroken(report(service), brokenSeq, reason);
    }

    @Test
    @DisplayName("catena riscritta tutta in modo coerente dopo l'ancoraggio: la rivelano l'ancora nel database e quella nei log")
    void consistentRewriteIsCaughtByTheAnchor() {
        String service = uid("chn-rewrite").toLowerCase();
        append(service, 5);
        List<ILoggingEvent> logged = anchorLogDuring(anchorJob::anchor);
        Matcher line = anchorLine(logged, service);
        assertThat(line.group(2)).isEqualTo("5");
        assertThat(report(service).path("anchorsChecked").asInt()).isEqualTo(1);

        // Chi amministra il database cambia la voce 3, ricalcola gli hash di tutte le seguenti e della testa e cancella
        // l'ancora giornaliera.
        asDatabaseAdmin(() -> {
            jdbc.sql("""
                            UPDATE audit_entry SET summary = 'Nessuna modifica',
                                   content_hash = audit_content_hash('Nessuna modifica', before, after)
                            WHERE service = ? AND seq = 3""").param(service).update();
            for (long seq = 3; seq <= 5; seq++) {
                jdbc.sql("""
                                UPDATE audit_entry e SET prev_hash = p.entry_hash FROM audit_entry p
                                WHERE e.service = ? AND e.seq = ? AND p.service = e.service AND p.seq = e.seq - 1""")
                        .params(service, seq).update();
                jdbc.sql("""
                                UPDATE audit_entry SET entry_hash = audit_entry_hash(service, seq, prev_hash, id, event_id,
                                       at, actor_role, actor_name, entity_type, entity_id, action, correlation_id,
                                       content_hash)
                                WHERE service = ? AND seq = ?""").params(service, seq).update();
            }
            jdbc.sql("""
                            UPDATE audit_chain_head h SET entry_hash = e.entry_hash FROM audit_entry e
                            WHERE h.service = ? AND e.service = h.service AND e.seq = h.seq""").param(service).update();
        });
        assertBroken(report(service), 5, "ANCHOR_MISMATCH");

        asDatabaseAdmin(() -> jdbc.sql("DELETE FROM audit_anchor WHERE service = ?").param(service).update());
        assertOk(report(service)); // nel database non resta nulla che lo riveli
        JsonNode withLog = reportWith(service, Long.parseLong(line.group(2)), line.group(3));
        assertBroken(withLog, 5, "ANCHOR_MISMATCH");
        assertThat(withLog.path("expectedAnchor").path("result").asString()).isEqualTo("MISMATCH");
    }

    // ---------- scritture ammesse e rifiutate (fase expand) ----------

    @Test
    @DisplayName("rifiutati (42501): campi della voce e della catena, cancellazioni in mezzo, TRUNCATE, testa e ancore")
    void writesRejected() {
        String service = uid("chn-insert-only").toLowerCase();
        append(service, 3);
        anchorJob.anchor();
        List<Long> before = seqs(service);

        assertRejected(() -> jdbc.sql("UPDATE audit_entry SET actor_name = 'mallory' WHERE service = ?").param(service).update());
        assertRejected(() -> jdbc.sql("UPDATE audit_entry SET entry_hash = repeat('c', 64) WHERE service = ?").param(service).update());
        assertRejected(() -> jdbc.sql("UPDATE audit_entry SET seq = seq + 100 WHERE service = ?").param(service).update());
        assertRejected(() -> jdbc.sql("DELETE FROM audit_entry WHERE service = ? AND seq = 2").param(service).update());
        assertRejected(() -> jdbc.sql("TRUNCATE audit_entry").update());
        assertRejected(() -> jdbc.sql("UPDATE audit_chain_head SET seq = 0 WHERE service = ?").param(service).update());
        assertRejected(() -> jdbc.sql("DELETE FROM audit_chain_head WHERE service = ?").param(service).update());
        assertRejected(() -> jdbc.sql("INSERT INTO audit_chain_head (service, seq, entry_hash) VALUES (?, 9, 'x')")
                .param(uid("chn-fake")).update());
        assertRejected(() -> jdbc.sql("TRUNCATE audit_chain_head").update());
        assertRejected(() -> jdbc.sql("UPDATE audit_anchor SET seq = 0 WHERE service = ?").param(service).update());
        assertRejected(() -> jdbc.sql("DELETE FROM audit_anchor WHERE service = ?").param(service).update());
        assertRejected(() -> jdbc.sql("TRUNCATE audit_anchor").update());
        // Le ancore PURGE le scrive solo la cancellazione; un'ancora non si retrodata.
        assertRejected(() -> jdbc.sql("INSERT INTO audit_anchor (service, seq, entry_hash, kind, anchored_at) "
                + "VALUES (?, 1, repeat('a', 64), 'PURGE', timestamptz '2020-01-01')").param(service).update());
        jdbc.sql("INSERT INTO audit_anchor (service, seq, entry_hash, kind, anchored_at, entry_at) "
                + "VALUES (?, 3, ?, 'BACKFILL', timestamptz '2020-01-01', timestamptz '2020-01-01')")
                .params(service, entryHash(service, 3)).update();
        assertThat(jdbc.sql("SELECT anchored_at > now() - interval '1 hour' AND entry_at IS NULL FROM audit_anchor "
                + "WHERE service = ? AND kind = 'BACKFILL'").param(service).query(Boolean.class).single())
                .as("istante fissato dal database, entry_at solo per PURGE").isTrue();
        // Le prove REDACT le scrive solo il database.
        assertRejected(() -> jdbc.sql("INSERT INTO audit_entry (id, event_id, at, service, entity_type, entity_id, action) "
                + "VALUES (?, ?, now(), 'audit.redaction', 'AUDIT_ENTRY', 'x', 'REDACT')").params(uid("AUD"), uid("EVT")).update());
        assertRejected(() -> jdbc.sql("INSERT INTO audit_entry (id, event_id, at, service, entity_type, entity_id, action) "
                + "VALUES (?, ?, now(), ?, 'AUDIT_ENTRY', 'x', 'REDACT')").params(uid("AUD"), uid("EVT"), service).update());
        // Un UPDATE che tocca solo redacted_at non cambia nulla (lo decide il trigger).
        jdbc.sql("UPDATE audit_entry SET redacted_at = now() WHERE service = ?").param(service).update();

        assertThat(seqs(service)).isEqualTo(before);
        assertThat(anchorKinds(service)).containsExactly("DAILY:3", "BACKFILL:3");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_entry WHERE service = ? AND redacted_at IS NOT NULL")
                .param(service).query(Long.class).single()).isZero();
        assertOk(report(service));
    }

    @Test
    @DisplayName("il flag della testa non resta alzato: dopo un inserimento, nella stessa transazione, la testa è protetta")
    void headFlagDoesNotLeak() {
        String service = uid("chn-flag").toLowerCase();
        append(service, 1);
        assertRejected(() -> asApplication(() -> {
            audits.insert(record(service, 2, T));
            jdbc.sql("UPDATE audit_chain_head SET seq = 99 WHERE service = ?").param(service).update();
        }));
        assertThat(seqs(service)).containsExactly(1L);
        assertOk(report(service));
    }

    @Test
    @DisplayName("contenuto riscritto con un UPDATE semplice (versione precedente): ammesso, marcato, con prova REDACT senza membro")
    void contentUpdateLeavesEvidence() {
        String service = uid("chn-content").toLowerCase();
        append(service, 3);
        String id = entryId(service, 2);
        assertThat(jdbc.sql("UPDATE audit_entry SET summary = ?, before = NULL WHERE id = ?")
                .params("Membro anonimo", id).update()).isEqualTo(1);
        String content = jdbc.sql("SELECT audit_content_hash(summary, before, after) FROM audit_entry WHERE id = ?")
                .param(id).query(String.class).single();
        assertThat(redactions(id)).containsExactly("-|2|" + content);
        JsonNode report = report(service);
        assertOk(report);
        assertThat(report.path("redacted").asLong()).isEqualTo(1);
        assertOk(report(AuditChainRepository.REDACTION_SERVICE));

        // La stessa voce alterata di nuovo da chi amministra il database: non corrisponde più alla sua prova.
        asDatabaseAdmin(() -> jdbc.sql("UPDATE audit_entry SET summary = 'altro' WHERE id = ?").param(id).update());
        assertBroken(report(service), 2, "CONTENT_ALTERED");
    }

    @Test
    @DisplayName("audit_redact: fatto del membro nell'event store, voce che lo riguarda, valori di almeno 3 caratteri")
    void auditRedactPreconditions() {
        String service = uid("chn-redact").toLowerCase();
        String member = uid("MBR-RDX");
        audits.insert(new AuditRecord(uid("AUD"), uid("EVT"), T, "CARE", "carla.care", service, "MEMBER", member,
                "UPDATE", "Aggiornato Ottavio Quintilio", null, null, null));
        append(service, 1); // voce di un'altra entità: non riguarda il membro
        String own = entryId(service, 1);
        String unrelated = entryId(service, 2);
        String call = "SELECT audit_redact(?, ?, ?, 'Aggiornato Membro anonimo', NULL, NULL, cast(? AS jsonb), ?)";

        assertRejected(() -> jdbc.sql(call).params(member, uid("EVT-NONE"), own, "[]", "COR-X").query(Boolean.class).single());
        String fact = anonymizationFact(member);
        String otherFact = anonymizationFact(uid("MBR-ALTRO"));
        assertRejected(() -> jdbc.sql(call).params(member, otherFact, own, "[]", "COR-X").query(Boolean.class).single());
        assertRejected(() -> jdbc.sql(call).params(member, fact, unrelated, "[\"Ottavio Quintilio\"]", "COR-X")
                .query(Boolean.class).single());
        // Un valore vuoto o troppo corto non basta a dire che la voce riguarda il membro.
        assertRejected(() -> jdbc.sql(call).params(member, fact, unrelated, "[\" \", \"ca\", \"\"]", "COR-X")
                .query(Boolean.class).single());
        // Revisione m-b: un valore conta solo come parola intera («ata» non è in «Modificata», «campagna» sì).
        assertRejected(() -> jdbc.sql(call).params(member, fact, unrelated, "[\"ata\", \"Campagn\"]", "COR-X")
                .query(Boolean.class).single());
        assertRejected(() -> jdbc.sql(call).params("", fact, own, "[]", "COR-X").query(Boolean.class).single());
        assertRejected(() -> jdbc.sql(call).params(member, "", own, "[]", "COR-X").query(Boolean.class).single());
        assertThat(jdbc.sql(call).params(member, fact, own, "[]", "COR-ANON").query(Boolean.class).single()).isTrue();

        assertThat(redactions(own)).singleElement().asString().startsWith(member + "|1|");
        // Revisione m-e: il fatto conta per il suo id, non per il payload (troncato a 8 KB, senza lo stato); la voce di
        // un'altra entità conta se cita un valore del membro come parola intera, anche con maiuscole diverse dal profilo.
        String truncated = anonymizationFact(member, "{\"data\": {\"truncated\": true}}");
        audits.insert(new AuditRecord(uid("AUD"), uid("EVT"), T, "CARE", "carla.care", service, "REDEMPTION", uid("RDM"),
                "UPDATE", "Spedizione a OTTAVIO Quintilio", null, null, null));
        String mentions = entryId(service, 3);
        assertThat(jdbc.sql(call).params(member, truncated, mentions, "[\"OTTAVIO Quintilio\"]", "COR-T")
                .query(Boolean.class).single()).isTrue();
        assertThat(redactions(mentions)).singleElement().asString().startsWith(member + "|3|");
        assertThat(jdbc.sql("SELECT correlation_id || '|' || (after ->> 'eventId') || '|' || (before IS NULL) "
                        + "FROM audit_entry WHERE service = 'audit.redaction' AND entity_id = ?")
                .param(own).query(String.class).single()).isEqualTo("COR-ANON|" + fact + "|true");
        assertOk(report(service));
    }

    @Test
    @DisplayName("evento audit del bus che rivendica le prove REDACT: rifiutato (resta nell'event store, nessuna voce)")
    void busEventsCannotClaimRedactionEvidence() {
        String entity = uid("CMP-REDACT");
        List<String> events = new ArrayList<>();
        for (String[] claim : new String[][]{{"audit.redaction", "UPDATE"}, {"campaign", "REDACT"}}) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("service", claim[0]);
            data.put("entityType", "AUDIT_ENTRY");
            data.put("entityId", entity);
            data.put("action", claim[1]);
            data.put("summary", "Prova falsa");
            data.put("after", Map.of("contentHash", "f".repeat(64), "service", "campaign", "seq", 1));
            String eventId = uid("EVT-FAKE");
            ObjectNode env = envelope(eventId, AUDIT_TYPE, "urn:loyaltyhub:service:campaign", "AUDIT_ENTRY:" + entity,
                    uid("COR"), null, T, data);
            env.put("lhactor", "ADMIN:mallory");
            publish("lh.audit.v1", env);
            events.add(eventId);
        }
        events.forEach(this::awaitStored);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_entry WHERE entity_id = ?").param(entity).query(Long.class).single())
                .isZero();
    }

    // ---------- retention ----------

    @Test
    @DisplayName("retention: cancella solo la parte più vecchia della catena, lascia un'ancora PURGE, la verifica regge")
    void retentionKeepsTheChainVerifiable() {
        String service = uid("chn-retention").toLowerCase();
        Instant[] ats = {Instant.parse("1990-01-01T00:00:00Z"), Instant.parse("1991-01-01T00:00:00Z"),
                Instant.parse("2030-01-01T00:00:00Z"), Instant.parse("1992-01-01T00:00:00Z")};
        for (int i = 0; i < ats.length; i++) {
            audits.insert(record(service, i + 1, ats[i]));
        }
        AuditRepository.Purge purge = audits.deleteBefore(Instant.parse("2001-01-01T00:00:00Z"));

        // La voce 4 (1992) è vecchia ma segue la 3, recente: resta finché non scade anche la 3 (la catena non si buca).
        assertThat(purge.anchors()).anySatisfy(a -> {
            assertThat(a.service()).isEqualTo(service);
            assertThat(a.seq()).isEqualTo(2);
            assertThat(a.kind()).isEqualTo(AuditAnchor.PURGE);
        });
        assertThat(seqs(service)).containsExactly(3L, 4L);
        assertThat(anchorKinds(service)).containsExactly("PURGE:2");
        assertThat(audits.retentionStalls(AuditRetentionGuard.MIN_DAYS)).anySatisfy(s -> {
            assertThat(s.service()).isEqualTo(service);
            assertThat(s.blockingSeq()).isEqualTo(3);
            assertThat(s.expiredKept()).isEqualTo(1);
        });
        JsonNode report = report(service);
        assertOk(report);
        assertThat(report.path("firstSeq").asLong()).isEqualTo(3);
        assertThat(report.path("checked").asLong()).isEqualTo(2);
        assertThat(report.path("anchorsChecked").asInt()).isEqualTo(1);

        audits.insert(record(service, 5, T));
        assertThat(seqs(service)).containsExactly(3L, 4L, 5L);
        assertOk(report(service));
    }

    @Test
    @DisplayName("retention che svuota una catena: la testa resta ancorata e le voci nuove proseguono la numerazione")
    void retentionEmptiesAChain() {
        String service = uid("chn-retention-all").toLowerCase();
        audits.insert(record(service, 1, Instant.parse("1990-01-01T00:00:00Z")));
        audits.insert(record(service, 2, Instant.parse("1990-06-01T00:00:00Z")));
        audits.deleteBefore(Instant.parse("2001-01-01T00:00:00Z"));
        assertThat(seqs(service)).isEmpty();
        JsonNode empty = report(service);
        assertOk(empty);
        assertThat(empty.path("headSeq").asLong()).isEqualTo(2);
        assertThat(empty.path("checked").asLong()).isZero();

        audits.insert(record(service, 3, T));
        assertThat(seqs(service)).containsExactly(3L);
        assertOk(report(service));
    }

    @Test
    @DisplayName("retention con una soglia nel futuro: limitata all'età minima, le voci recenti restano")
    void purgeIsClampedToTheMinimumAge() {
        String service = uid("chn-clamp").toLowerCase();
        audits.insert(record(service, 1, Instant.parse("1990-01-01T00:00:00Z")));
        append(service, 2);
        jdbc.sql("SELECT count(*) FROM audit_purge_before(now() + interval '3650 days')").query(Long.class).single();
        assertThat(seqs(service)).containsExactly(2L, 3L);
        assertOk(report(service));
    }

    @Test
    @DisplayName("DELETE senza flag di voci recenti (versione precedente o credenziali applicative): ammesso, ma PURGE_TOO_RECENT")
    void recentPrefixDeleteIsFlagged() {
        String service = uid("chn-prefix").toLowerCase();
        append(service, 4);
        List<ILoggingEvent> logged = anchorLogDuring(anchorJob::anchor);
        Matcher line = anchorLine(logged, service);

        audits.insert(record(service, 5, T));
        assertThat(jdbc.sql("DELETE FROM audit_entry WHERE service = ? AND seq <= 4").param(service).update()).isEqualTo(4);
        assertThat(anchorKinds(service)).containsExactly("DAILY:4", "PURGE:4");
        assertBroken(report(service), 4, "PURGE_TOO_RECENT");
        // L'ancora dei log sulla voce 4, ormai cancellata: mai MATCH, anche se coincide con il prev_hash della voce 5.
        JsonNode checked = reportWith(service, Long.parseLong(line.group(2)), line.group(3));
        assertThat(checked.path("expectedAnchor").path("result").asString()).isEqualTo("PURGED");
        assertThat(checked.path("expectedAnchor").path("purgedAt").asString()).isNotBlank();
        // Un hash diverso per la stessa voce resta MISMATCH (il prev_hash della voce 5 lo smentisce).
        asDatabaseAdmin(() -> jdbc.sql("DELETE FROM audit_anchor WHERE service = ? AND kind = 'PURGE'").param(service).update());
        JsonNode other = reportWith(service, 4, "a".repeat(64));
        assertThat(other.path("expectedAnchor").path("result").asString()).isEqualTo("MISMATCH");
    }

    @Test
    @DisplayName("ancora PURGE falsificata prima della cancellazione: la verifica la segnala, il DELETE è rifiutato")
    void forgedPurgeAnchorIsRejected() {
        String service = uid("chn-forged-purge").toLowerCase();
        append(service, 3);
        // Con le credenziali applicative e il flag alzato a mano: PURGE sulla voce 2, datata 1990.
        asApplication(() -> {
            jdbc.sql("SELECT set_config('loyaltyhub.audit_purge_anchor', 'on', true)").query(String.class).single();
            jdbc.sql("INSERT INTO audit_anchor (service, seq, entry_hash, kind, entry_at) "
                    + "VALUES (?, 2, ?, 'PURGE', timestamptz '1990-01-01')").params(service, entryHash(service, 2)).update();
        });
        assertBroken(report(service), 2, "ANCHOR_MISMATCH"); // una voce cancellata non torna: la PURGE è falsa
        assertRejected(() -> jdbc.sql("DELETE FROM audit_entry WHERE service = ? AND seq <= 2").param(service).update());
        assertThat(seqs(service)).containsExactly(1L, 2L, 3L);
        assertThat(anchorKinds(service)).containsExactly("PURGE:2");
    }

    // ---------- servizi spariti ----------

    @Test
    @DisplayName("voci e testa cancellate con le credenziali dell'applicazione e catena rifatta: le ancore la rivelano")
    void vanishedAndForgedChainIsReported() {
        String service = uid("chn-vanish").toLowerCase();
        append(service, 5);
        anchorJob.anchor();
        asApplication(() -> {
            jdbc.sql("SELECT set_config('loyaltyhub.audit_chain', 'on', true)").query(String.class).single();
            jdbc.sql("DELETE FROM audit_chain_head WHERE service = ?").param(service).update();
            jdbc.sql("SELECT set_config('loyaltyhub.audit_chain', '', true)").query(String.class).single();
            jdbc.sql("DELETE FROM audit_entry WHERE service = ?").param(service).update();
        });
        assertThat(seqs(service)).isEmpty();
        assertBroken(report(service), 5, "PURGE_TOO_RECENT"); // voci cancellate da giovani, restano le ancore
        // Senza voci né testa il servizio compare lo stesso nella verifica completa, grazie alle sue ancore.
        Resp before = verifyCall(null, actor("ADMIN"));
        List<String> listed = new ArrayList<>();
        before.body().path("services").forEach(s -> listed.add(s.path("service").asString() + ":" + s.path("status").asString()));
        assertThat(listed).contains(service + ":BROKEN");

        append(service, 3); // catena rifatta dalla genesi
        assertBroken(report(service), 5, "PURGE_TOO_RECENT");
        asDatabaseAdmin(() -> jdbc.sql("DELETE FROM audit_anchor WHERE service = ? AND kind = 'PURGE'").param(service).update());
        assertBroken(report(service), 4, "TAIL_MISSING"); // resta l'ancora DAILY della voce 5
        Resp all = verifyCall(null, actor("ADMIN"));
        List<String> broken = new ArrayList<>();
        all.body().path("services").forEach(s -> {
            if ("BROKEN".equals(s.path("status").asString())) {
                broken.add(s.path("service").asString());
            }
        });
        assertThat(broken).contains(service);
        assertThat(all.body().path("status").asString()).isEqualTo("BROKEN");
    }

    @Test
    @DisplayName("ancora dei log per un servizio di cui non resta nulla: BROKEN, non 404")
    void expectedAnchorOnAnUnknownService() {
        JsonNode r = reportWith("chn-mai-esistito", 3, "a".repeat(64));
        assertBroken(r, 1, "TAIL_MISSING");
        assertThat(r.path("expectedAnchor").path("result").asString()).isEqualTo("MISSING");
    }

    // ---------- ancoraggio ----------

    @Test
    @DisplayName("ancoraggio giornaliero: solo catene cresciute e verificate, riga nei log, una catena interrotta non si ancora")
    void dailyAnchoring() {
        String service = uid("chn-anchor").toLowerCase();
        append(service, 3);
        List<ILoggingEvent> logged = anchorLogDuring(anchorJob::anchor);
        Matcher line = anchorLine(logged, service);
        assertThat(line.group(2)).isEqualTo("3");
        assertThat(line.group(3)).isEqualTo(entryHash(service, 3));
        assertThat(line.group(4)).isEqualTo("DAILY");
        ILoggingEvent event = logged.stream().filter(e -> e.getFormattedMessage().contains(service)).findFirst().orElseThrow();
        assertThat(event.getKeyValuePairs()).extracting(kv -> kv.key)
                .contains("service", "seq", "entryHash", "kind", "anchoredAt");
        assertThat(anchorKinds(service)).containsExactly("DAILY:3");

        JsonNode match = reportWith(service, 3, line.group(3));
        assertOk(match);
        assertThat(match.path("expectedAnchor").path("result").asString()).isEqualTo("MATCH");
        assertBroken(reportWith(service, 3, "e".repeat(64)), 3, "ANCHOR_MISMATCH");

        assertThat(anchorLogDuring(anchorJob::anchor)).noneMatch(e -> e.getFormattedMessage().contains(service));
        assertThat(anchorKinds(service)).as("nessuna voce nuova, nessuna ancora in più").containsExactly("DAILY:3");
        assertThat(chain.insertAnchor(new AuditChainHead(service, 3, entryHash(service, 3)), AuditAnchor.DAILY))
                .as("un'altra replica non duplica l'ancora").isEmpty();

        append(service, 2);
        anchorJob.anchor();
        assertThat(anchorKinds(service)).containsExactly("DAILY:3", "DAILY:5");

        append(service, 1);
        asDatabaseAdmin(() -> jdbc.sql("UPDATE audit_entry SET summary = 'x' WHERE service = ? AND seq = 6")
                .param(service).update());
        assertThat(anchorJob.anchor()).noneMatch(a -> a.service().equals(service));
        assertThat(anchorKinds(service)).containsExactly("DAILY:3", "DAILY:5");
        assertBroken(report(service), 6, "CONTENT_ALTERED");
    }

    // ---------- concorrenza ----------

    @Test
    @DisplayName("accodamenti concorrenti (8 thread, duplicati inclusi): catena senza buchi né doppioni, verifica OK")
    void concurrentAppendsStayGapless() throws Exception {
        String service = uid("chn-concurrent").toLowerCase();
        String other = uid("chn-concurrent-b").toLowerCase();
        List<AuditRecord> shared = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            shared.add(record(service, 1000 + i, T));
        }
        int threads = 8;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int thread = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        audits.insert(record(service, thread * 100 + i, T.plusMillis(i)));
                        if (i % 5 == 0) {
                            audits.insert(record(other, thread * 100 + i, T));
                        }
                        // Ogni thread riconsegna le stesse 10 voci (stesso event_id, id diverso): una sola in catena.
                        AuditRecord dup = shared.get((thread + i) % shared.size());
                        audits.insert(new AuditRecord(uid("AUD"), dup.eventId(), dup.at(), dup.actorRole(),
                                dup.actorName(), dup.service(), dup.entityType(), dup.entityId(), dup.action(),
                                dup.summary(), dup.before(), dup.after(), dup.correlationId()));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        long expected = threads * perThread + shared.size();
        assertThat(seqs(service)).containsExactlyElementsOf(LongStream.rangeClosed(1, expected).boxed().toList());
        assertThat(headSeq(service)).isEqualTo(expected);
        assertThat(seqs(other)).hasSize(threads * 5).last().isEqualTo((long) threads * 5);
        assertOk(report(service));
        assertOk(report(other));
    }

    // ---------- anonimizzazione ----------

    /**
     * Revisione P9: un soprannome o un nome che è parte di una parola strutturale («Anon» in ANONYMIZED, «Zed») o uguale
     * a uno stato («Active») non deve corrompere i fatti conservati né far fallire l'anonimizzazione (fatto in DLQ, dati
     * personali rimasti).
     */
    @ParameterizedTest(name = "nome {0}, soprannome {1}")
    @DisplayName("anonimizzazione con valori che coincidono con parti di stati: riesce, gli stati restano, i dati personali no")
    @CsvSource({"Ottavio,Anon", "Zed,Zed", "Ottavio,Active"})
    void anonymizationNeverCorruptsStructuralValues(String firstName, String nickname) {
        String service = uid("chn-p9").toLowerCase();
        String memberId = uid("MBR-P9");
        String email = "p9." + memberId.toLowerCase() + "@example.test";
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("memberId", memberId);
        profile.put("firstName", firstName);
        profile.put("lastName", "Quintilio");
        profile.put("nickname", nickname);
        profile.put("email", email);
        profile.put("status", "ACTIVE");
        String registered = uid("EVT-REG");
        publish("lh.facts.v1", envelope(registered, FACT + "member.registered", "urn:loyaltyhub:service:member",
                "member:" + memberId, uid("COR"), null, T, profile));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", service);
        data.put("entityType", "MEMBER");
        data.put("entityId", memberId);
        data.put("action", "UPDATE");
        data.put("summary", "Aggiornato " + firstName + " Quintilio (" + nickname + ")");
        data.put("before", Map.of("nickname", nickname, "status", "ACTIVE"));
        data.put("after", Map.of("nickname", "nuovo", "status", "ACTIVE"));
        ObjectNode audit = envelope(uid("EVT-AUD"), AUDIT_TYPE, "urn:loyaltyhub:service:member", "MEMBER:" + memberId,
                uid("COR"), null, T.plusSeconds(1), data);
        audit.put("lhactor", "CARE:carla.care");
        publish("lh.audit.v1", audit);
        await("voce di audit del membro", () -> seqs(service).size() == 1, 20_000);

        Map<String, Object> change = new LinkedHashMap<>();
        change.put("memberId", memberId);
        change.put("previousStatus", "ACTIVE");
        change.put("newStatus", "ANONYMIZED");
        String anonymization = uid("EVT-ANON");
        publish("lh.facts.v1", envelope(anonymization, FACT + "member.status.changed", "urn:loyaltyhub:service:member",
                "member:" + memberId, uid("COR-ANON"), null, T.plusSeconds(2), change));
        await("voce di audit anonimizzata", () -> jdbc.sql(
                        "SELECT count(*) FROM audit_entry WHERE service = ? AND redacted_at IS NOT NULL")
                .param(service).query(Long.class).single() == 1, 20_000);

        assertThat(jdbc.sql("SELECT payload -> 'data' ->> 'previousStatus' || '>' || (payload -> 'data' ->> 'newStatus') "
                + "FROM event_store WHERE event_id = ?").param(anonymization).query(String.class).single())
                .isEqualTo("ACTIVE>ANONYMIZED");
        assertThat(jdbc.sql("SELECT payload -> 'data' ->> 'status' FROM event_store WHERE event_id = ?").param(registered)
                .query(String.class).single()).isEqualTo("ACTIVE");
        String memberCopies = String.join(" ", jdbc.sql("SELECT payload::text FROM event_store WHERE member_id = ?")
                .param(memberId).query(String.class).list());
        assertThat(memberCopies).doesNotContain(email).doesNotContain("\"firstName\"").doesNotContain("\"nickname\"");
        String auditText = jdbc.sql("SELECT summary || coalesce(before::text, '') || coalesce(after::text, '') "
                + "FROM audit_entry WHERE service = ?").param(service).query(String.class).single();
        assertThat(auditText).doesNotContainIgnoringCase(firstName + " Quintilio").contains("ACTIVE")
                .doesNotContain("\"nickname\"");
        assertThat(redactions(entryId(service, 1))).singleElement().asString().startsWith(memberId + "|1|");
        JsonNode report = report(service);
        assertOk(report);
        assertThat(report.path("redacted").asLong()).isEqualTo(1);
    }

    /**
     * Revisione P18: i testi liberi che un operatore scrive ({@code reason} di un cambio di stato, di una rettifica dei
     * punti, del diff dell'audit) e il {@code subject} di un'azione ({@code email:…}, {@code external:…}) citano il membro:
     * dopo l'anonimizzazione non devono più contenerne i dati.
     */
    @Test
    @DisplayName("anonimizzazione: reason dei fatti e dell'audit e subject email/external ripuliti, stati intatti")
    void anonymizationScrubsFreeTextReasonsAndSubjects() {
        String service = uid("chn-p18").toLowerCase();
        String memberId = uid("MBR-P18");
        String email = "p18." + memberId.toLowerCase() + "@example.test";
        String external = uid("EXT-P18");
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("memberId", memberId);
        profile.put("firstName", "Ottavio");
        profile.put("lastName", "Quintilio");
        profile.put("email", email);
        profile.put("externalId", external);
        profile.put("status", "ACTIVE");
        publish("lh.facts.v1", envelope(uid("EVT-REG"), FACT + "member.registered", "urn:loyaltyhub:service:member",
                "member:" + memberId, uid("COR"), null, T, profile));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", service);
        data.put("entityType", "MEMBER");
        data.put("entityId", memberId);
        data.put("action", "UPDATE");
        data.put("summary", "Sospeso");
        data.put("after", Map.of("status", "SUSPENDED", "reason", "Reclamo di Ottavio Quintilio (" + email + ")"));
        ObjectNode audit = envelope(uid("EVT-AUD"), AUDIT_TYPE, "urn:loyaltyhub:service:member", "MEMBER:" + memberId,
                uid("COR"), null, T.plusSeconds(1), data);
        audit.put("lhactor", "CARE:carla.care");
        publish("lh.audit.v1", audit);
        String adjusted = uid("EVT-ADJ");
        publish("lh.facts.v1", envelope(adjusted, FACT + "wallet.points.adjusted", "urn:loyaltyhub:service:wallet",
                "member:" + memberId, uid("COR"), null, T.plusSeconds(2),
                Map.of("memberId", memberId, "currency", "PTS", "amount", 10,
                        "reason", "Rimborso chiesto da Ottavio Quintilio")));
        String byEmail = uid("EVT-ACT");
        publish("lh.actions.v1", envelope(byEmail, ACTION + "app.login.daily", "urn:loyaltyhub:source:app",
                "email:" + email, uid("COR"), null, T.plusSeconds(3), Map.of("channel", "APP")));
        String byExternal = uid("EVT-ACT");
        publish("lh.actions.v1", envelope(byExternal, ACTION + "app.login.daily", "urn:loyaltyhub:source:app",
                "external:" + external, uid("COR"), null, T.plusSeconds(3), Map.of("channel", "APP")));
        for (String id : List.of(adjusted, byEmail, byExternal)) {
            awaitStored(id);
        }
        await("voce di audit del membro", () -> seqs(service).size() == 1, 20_000);

        Map<String, Object> change = new LinkedHashMap<>();
        change.put("memberId", memberId);
        change.put("previousStatus", "SUSPENDED");
        change.put("newStatus", "ANONYMIZED");
        change.put("reason", "Diritto all'oblio chiesto da Ottavio Quintilio via " + email);
        String anonymization = uid("EVT-ANON");
        publish("lh.facts.v1", envelope(anonymization, FACT + "member.status.changed", "urn:loyaltyhub:service:member",
                "member:" + memberId, uid("COR-ANON"), null, T.plusSeconds(4), change));
        await("voce di audit anonimizzata", () -> jdbc.sql(
                        "SELECT count(*) FROM audit_entry WHERE service = ? AND redacted_at IS NOT NULL")
                .param(service).query(Long.class).single() == 1, 20_000);

        JsonNode fact = storedData(anonymization);
        assertThat(fact.path("reason").asString()).isEqualTo("Diritto all'oblio chiesto da Membro anonimo via Membro anonimo");
        assertThat(fact.path("previousStatus").asString() + ">" + fact.path("newStatus").asString())
                .isEqualTo("SUSPENDED>ANONYMIZED");
        assertThat(storedData(adjusted).path("reason").asString()).isEqualTo("Rimborso chiesto da Membro anonimo");
        assertThat(storedData(adjusted).path("currency").asString()).isEqualTo("PTS");
        assertThat(storedSubject(byEmail)).isEqualTo("email:Membro anonimo");
        assertThat(storedSubject(byExternal)).isEqualTo("external:Membro anonimo");
        JsonNode after = json.readTree(jdbc.sql("SELECT after::text FROM audit_entry WHERE service = ?").param(service)
                .query(String.class).single());
        assertThat(after.path("reason").asString()).isEqualTo("Reclamo di Membro anonimo (Membro anonimo)");
        assertThat(after.path("status").asString()).isEqualTo("SUSPENDED");
        String everything = String.join(" ", jdbc.sql("SELECT payload::text FROM event_store WHERE event_id IN (?, ?, ?, ?)")
                .params(anonymization, adjusted, byEmail, byExternal).query(String.class).list());
        assertThat(everything).doesNotContain("Ottavio").doesNotContain(email).doesNotContain(external);
        assertOk(report(service));
    }

    private JsonNode storedData(String eventId) {
        return json.readTree(jdbc.sql("SELECT payload -> 'data' FROM event_store WHERE event_id = ?").param(eventId)
                .query(String.class).single());
    }

    private String storedSubject(String eventId) {
        return jdbc.sql("SELECT payload ->> 'subject' FROM event_store WHERE event_id = ?").param(eventId)
                .query(String.class).single();
    }

    @Test
    @DisplayName("due anonimizzazioni concorrenti sulla stessa voce: in fila, nessun deadlock, catena valida")
    void concurrentAnonymizationsDoNotDeadlock() throws Exception {
        String service = uid("chn-p11").toLowerCase();
        String a = uid("MBR-P11A");
        String b = uid("MBR-P11B");
        String factA = anonymizationFact(a);
        String factB = anonymizationFact(b);
        audits.insert(new AuditRecord(uid("AUD"), uid("EVT"), T, "CARE", "c", service, "MEMBER", a, "UPDATE",
                "voce di A", null, null, null));
        audits.insert(new AuditRecord(uid("AUD"), uid("EVT"), T, "CARE", "c", service, "TRANSFER", b, "UPDATE",
                "trasferimento da " + a + " a " + b, null, null, null));
        String e1 = entryId(service, 1);
        String e2 = entryId(service, 2);
        String call = "SELECT audit_redact(?, ?, ?, ?, NULL, NULL, '[]'::jsonb, NULL)";
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch firstHolds = new CountDownLatch(1);
        try {
            Future<?> first = pool.submit(() -> asApplication(() -> {
                jdbc.sql(call).params(a, factA, e1, "A1").query(Boolean.class).single();
                firstHolds.countDown();
                pause(500);
                jdbc.sql(call).params(a, factA, e2, "A2").query(Boolean.class).single();
            }));
            Future<?> second = pool.submit(() -> {
                firstHolds.await();
                asApplication(() -> jdbc.sql(call).params(b, factB, e2, "B2").query(Boolean.class).single());
                return null;
            });
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT summary FROM audit_entry WHERE id = ?").param(e2).query(String.class).single())
                .as("la seconda anonimizzazione arriva dopo la prima").isEqualTo("B2");
        assertThat(redactions(e2)).hasSize(2);
        JsonNode report = report(service);
        assertOk(report);
        assertThat(report.path("redacted").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("anonimizzazione di un membro: voci ripulite, prova REDACT con membro e correlazione, catene valide")
    void anonymizationKeepsTheChain() {
        String service = uid("chn-member").toLowerCase();
        String memberId = uid("MBR-CHN");
        String anonCorrelation = uid("COR-ANON");
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("memberId", memberId);
        profile.put("firstName", "Ottavio");
        profile.put("lastName", "Quintilio");
        profile.put("email", "ottavio.quintilio@example.test");
        profile.put("status", "ACTIVE");
        publish("lh.facts.v1", envelope(uid("EVT-REG"), FACT + "member.registered", "urn:loyaltyhub:service:member",
                "member:" + memberId, uid("COR"), null, T, profile));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", service);
        data.put("entityType", "MEMBER");
        data.put("entityId", memberId);
        data.put("action", "UPDATE");
        data.put("summary", "Aggiornato Ottavio Quintilio");
        data.put("before", Map.of("email", "ottavio.quintilio@example.test", "status", "ACTIVE"));
        data.put("after", Map.of("email", "o.quintilio@example.test", "status", "ACTIVE"));
        ObjectNode audit = envelope(uid("EVT-CHN"), AUDIT_TYPE, "urn:loyaltyhub:service:member", "MEMBER:" + memberId,
                uid("COR"), null, T.plusSeconds(1), data);
        audit.put("lhactor", "CARE:carla.care");
        publish("lh.audit.v1", audit);
        // Voce di un'altra entità che cita il membro per nome: ripulita anche questa (valore inequivocabile).
        Map<String, Object> other = new LinkedHashMap<>(data);
        other.put("entityType", "REDEMPTION");
        other.put("entityId", uid("RDM"));
        other.put("summary", "Spedizione a Ottavio Quintilio");
        other.remove("before");
        other.remove("after");
        ObjectNode otherAudit = envelope(uid("EVT-CHN"), AUDIT_TYPE, "urn:loyaltyhub:service:reward", "REDEMPTION:x",
                uid("COR"), null, T.plusSeconds(2), other);
        otherAudit.put("lhactor", "CARE:carla.care");
        publish("lh.audit.v1", otherAudit);
        append(service, 1); // una voce che non lo riguarda, non toccata
        await("voci di audit del membro", () -> seqs(service).size() == 3, 20_000);

        Map<String, Object> anonymized = new LinkedHashMap<>();
        anonymized.put("memberId", memberId);
        anonymized.put("status", "ANONYMIZED");
        publish("lh.facts.v1", envelope(uid("EVT-ANON"), FACT + "member.updated", "urn:loyaltyhub:service:member",
                "member:" + memberId, anonCorrelation, null, T.plusSeconds(3), anonymized));
        await("voci di audit anonimizzate", () -> jdbc.sql(
                        "SELECT count(*) FROM audit_entry WHERE service = ? AND redacted_at IS NOT NULL")
                .param(service).query(Long.class).single() == 2, 20_000);

        String text = String.join(" ", jdbc.sql("SELECT summary || coalesce(before::text, '') || coalesce(after::text, '') "
                + "FROM audit_entry WHERE service = ?").param(service).query(String.class).list());
        assertThat(text).doesNotContain("Ottavio").doesNotContain("quintilio@");
        for (String id : jdbc.sql("SELECT id FROM audit_entry WHERE service = ? AND redacted_at IS NOT NULL")
                .param(service).query(String.class).list()) {
            assertThat(redactions(id)).singleElement().asString().startsWith(memberId + "|");
            assertThat(jdbc.sql("SELECT correlation_id FROM audit_entry WHERE service = 'audit.redaction' AND entity_id = ?")
                    .param(id).query(String.class).single()).isEqualTo(anonCorrelation);
        }
        assertThat(String.join(" ", jdbc.sql("SELECT summary || after::text FROM audit_entry WHERE service = 'audit.redaction' "
                + "AND action = 'REDACT' AND after ->> 'memberId' = ?").param(memberId).query(String.class).list()))
                .as("le prove non contengono dati personali").doesNotContain("Ottavio").doesNotContain("@");
        JsonNode report = report(service);
        assertOk(report);
        assertThat(report.path("redacted").asLong()).isEqualTo(2);
        assertThat(report.path("checked").asLong()).isEqualTo(3);
        assertOk(report(AuditChainRepository.REDACTION_SERVICE));
    }
}
