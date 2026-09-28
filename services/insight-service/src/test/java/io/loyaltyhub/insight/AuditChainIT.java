package io.loyaltyhub.insight;

import io.loyaltyhub.insight.application.AuditAnchorJob;
import io.loyaltyhub.insight.domain.AuditAnchor;
import io.loyaltyhub.insight.domain.AuditRecord;
import io.loyaltyhub.insight.infra.AuditRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Audit a prova di manomissione (F2-GRC-07 parte 1, ADR-043, ADR-044, docs/18 §3.15 punto 5, M8.12): catena di hash
 * per servizio calcolata all'inserimento, verifica ({@code GET /v1/audit/verify}), sola inserzione imposta dal
 * database, retention che lascia la catena verificabile, ancoraggio giornaliero, accodamenti concorrenti senza buchi,
 * anonimizzazione che non spezza la catena. Ogni caso usa servizi propri (il database è condiviso dal contesto).
 * Le alterazioni "da chi ha accesso al database" disattivano i trigger dentro una transazione, come farebbe chi ne ha
 * i privilegi: nessun'altra sessione vede la tabella senza protezione.
 */
class AuditChainIT extends TestbookInsBase {

    private static final Instant T = Instant.parse("2026-09-20T10:00:00.250001Z");

    @Autowired
    private AuditRepository audits;

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

    private Resp verifyCall(String service, String actor) {
        return call("GET", "/v1/audit/verify" + (service == null ? "" : "?service=" + service), actor, null);
    }

    /** Esito della catena di {@code service} da {@code GET /v1/audit/verify} (ADMIN). */
    private JsonNode report(String service) {
        Resp r = verifyCall(service, actor("ADMIN"));
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

    private List<String> anchorKinds(String service) {
        return jdbc.sql("SELECT kind || ':' || seq FROM audit_anchor WHERE service = ? ORDER BY id").param(service)
                .query(String.class).list();
    }

    /** Scrittura di chi ha accesso diretto al database: trigger spenti solo dentro questa transazione. */
    private void asDatabaseAdmin(Runnable statements) {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            jdbc.sql("ALTER TABLE audit_entry DISABLE TRIGGER USER").update();
            statements.run();
            jdbc.sql("ALTER TABLE audit_entry ENABLE TRIGGER USER").update();
        });
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

    // ---------- verifica ----------

    @Test
    @DisplayName("verifica: solo ADMIN (SPEC-GAP Q-399); servizio senza catena 404; senza filtro tutte le catene")
    void verifyAccess() {
        String service = uid("chn-access").toLowerCase();
        append(service, 1);
        for (String who : new String[]{"MARKETING", "CARE", "LEGAL", "ANALYST", "NONE"}) {
            Resp r = verifyCall(service, actor(who));
            assertThat(r.status()).as(who + " " + r.text()).isEqualTo(403);
        }
        assertThat(verifyCall("chn-sconosciuto", actor("ADMIN")).status()).isEqualTo(404);
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
                Arguments.of("attore", "UPDATE audit_entry SET actor_name = 'mallory' WHERE service = ? AND seq = 3",
                        3, "ENTRY_ALTERED"),
                Arguments.of("istante", "UPDATE audit_entry SET at = at + interval '1 second' WHERE service = ? AND seq = 3",
                        3, "ENTRY_ALTERED"),
                Arguments.of("oggetto", "UPDATE audit_entry SET entity_id = 'CMP-X' WHERE service = ? AND seq = 3",
                        3, "ENTRY_ALTERED"),
                Arguments.of("voce in mezzo rimossa", "DELETE FROM audit_entry WHERE service = ? AND seq = 3",
                        3, "MISSING_ENTRY"),
                Arguments.of("prima voce rimossa", "DELETE FROM audit_entry WHERE service = ? AND seq = 1",
                        2, "UNANCHORED_START"),
                Arguments.of("ultima voce rimossa", "DELETE FROM audit_entry WHERE service = ? AND seq = 5",
                        5, "TAIL_MISSING"),
                Arguments.of("testa riscritta", "UPDATE audit_chain_head SET entry_hash = repeat('b', 64) WHERE service = ?",
                        5, "HEAD_MISMATCH"));
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("alterazione diretta nel database: la verifica indica la voce esatta e il motivo")
    @MethodSource("tampering")
    void tamperingIsLocated(String what, String sql, long brokenSeq, String reason) {
        String service = uid("chn-tamper").toLowerCase();
        append(service, 5);
        assertOk(report(service));
        asDatabaseAdmin(() -> assertThat(jdbc.sql(sql).param(service).update()).isEqualTo(1));
        assertBroken(report(service), brokenSeq, reason);
    }

    @Test
    @DisplayName("catena riscritta tutta in modo coerente dopo l'ancoraggio: la rivela l'ancora giornaliera")
    void consistentRewriteIsCaughtByTheAnchor() {
        String service = uid("chn-rewrite").toLowerCase();
        append(service, 5);
        List<AuditAnchor> anchored = anchorJob.anchor();
        assertThat(anchored).anySatisfy(a -> {
            assertThat(a.service()).isEqualTo(service);
            assertThat(a.seq()).isEqualTo(5);
            assertThat(a.kind()).isEqualTo(AuditAnchor.DAILY);
        });
        assertThat(report(service).path("anchorsChecked").asInt()).isEqualTo(1);

        // Chi ha accesso al database cambia la voce 3 e ricalcola gli hash di tutte le seguenti e della testa.
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
    }

    // ---------- sola inserzione ----------

    @Test
    @DisplayName("sola inserzione: UPDATE, DELETE e TRUNCATE rifiutati su audit_entry e audit_anchor (42501)")
    void insertOnly() {
        String service = uid("chn-insert-only").toLowerCase();
        append(service, 3);
        anchorJob.anchor();
        List<Long> before = seqs(service);

        assertRejected(() -> jdbc.sql("UPDATE audit_entry SET summary = 'x' WHERE service = ?").param(service).update());
        assertRejected(() -> jdbc.sql("DELETE FROM audit_entry WHERE service = ?").param(service).update());
        assertRejected(() -> jdbc.sql("TRUNCATE audit_entry").update());
        assertRejected(() -> jdbc.sql("UPDATE audit_anchor SET seq = 0 WHERE service = ?").param(service).update());
        assertRejected(() -> jdbc.sql("DELETE FROM audit_anchor WHERE service = ?").param(service).update());
        assertRejected(() -> jdbc.sql("TRUNCATE audit_anchor").update());
        assertThat(seqs(service)).isEqualTo(before);
        assertThat(anchorKinds(service)).containsExactly("DAILY:3");
        assertOk(report(service));
    }

    @Test
    @DisplayName("percorsi controllati usati male: l'anonimizzazione non cambia i campi della voce, la retention non buca")
    void controlledPathsCannotBeAbused() {
        String service = uid("chn-abuse").toLowerCase();
        append(service, 3);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        assertRejected(() -> tx.executeWithoutResult(s -> {
            jdbc.sql("SELECT set_config('loyaltyhub.audit_write', 'redact', true)").query(String.class).single();
            jdbc.sql("UPDATE audit_entry SET actor_name = 'mallory' WHERE service = ? AND seq = 2").param(service).update();
        }));
        assertRejected(() -> tx.executeWithoutResult(s -> {
            jdbc.sql("SELECT set_config('loyaltyhub.audit_write', 'purge', true)").query(String.class).single();
            jdbc.sql("DELETE FROM audit_entry WHERE service = ? AND seq = 2").param(service).update();
        }));
        assertThat(seqs(service)).containsExactly(1L, 2L, 3L);
        assertOk(report(service));
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
        int purged = audits.deleteBefore(Instant.parse("2001-01-01T00:00:00Z"));

        // La voce 4 (1992) è vecchia ma segue la 3, recente: resta finché non scade anche la 3 (la catena non si buca).
        assertThat(purged).isGreaterThanOrEqualTo(2);
        assertThat(seqs(service)).containsExactly(3L, 4L);
        assertThat(anchorKinds(service)).containsExactly("PURGE:2");
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

    // ---------- ancoraggio ----------

    @Test
    @DisplayName("ancoraggio giornaliero: solo catene cresciute e verificate; una catena interrotta non si ancora")
    void dailyAnchoring() {
        String service = uid("chn-anchor").toLowerCase();
        append(service, 3);
        anchorJob.anchor();
        assertThat(anchorKinds(service)).containsExactly("DAILY:3");
        anchorJob.anchor();
        assertThat(anchorKinds(service)).as("nessuna voce nuova, nessuna ancora in più").containsExactly("DAILY:3");

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

    @Test
    @DisplayName("anonimizzazione di un membro: le voci di audit sono ripulite, marcate e la catena regge")
    void anonymizationKeepsTheChain() {
        String service = uid("chn-member").toLowerCase();
        String memberId = uid("MBR-CHN");
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
        append(service, 1); // una voce di un'altra entità, non toccata
        await("voce di audit del membro", () -> seqs(service).size() == 2, 20_000);

        Map<String, Object> anonymized = new LinkedHashMap<>();
        anonymized.put("memberId", memberId);
        anonymized.put("status", "ANONYMIZED");
        publish("lh.facts.v1", envelope(uid("EVT-ANON"), FACT + "member.updated", "urn:loyaltyhub:service:member",
                "member:" + memberId, uid("COR"), null, T.plusSeconds(2), anonymized));
        await("voce di audit anonimizzata", () -> jdbc.sql(
                        "SELECT count(*) FROM audit_entry WHERE service = ? AND redacted_at IS NOT NULL")
                .param(service).query(Long.class).single() == 1, 20_000);

        String summary = jdbc.sql("SELECT summary || coalesce(before::text, '') || coalesce(after::text, '') "
                + "FROM audit_entry WHERE service = ? AND entity_id = ?").params(service, memberId)
                .query(String.class).single();
        assertThat(summary).doesNotContain("Ottavio").doesNotContain("quintilio@");
        JsonNode report = report(service);
        assertOk(report);
        assertThat(report.path("redacted").asLong()).isEqualTo(1);
        assertThat(report.path("checked").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("contenuto riscritto senza passare dall'anonimizzazione: non è una voce anonimizzata, è un'alterazione")
    void rewriteOutsideRedactionIsTampering() {
        String service = uid("chn-fake-redact").toLowerCase();
        append(service, 2);
        assertThat(jdbc.sql("SELECT audit_redact(id, 'Membro anonimo', NULL, NULL) FROM audit_entry "
                + "WHERE service = ? AND seq = 1").param(service).query(Boolean.class).single()).isTrue();
        JsonNode redacted = report(service);
        assertOk(redacted);
        assertThat(redacted.path("redacted").asLong()).isEqualTo(1);

        asDatabaseAdmin(() -> jdbc.sql("UPDATE audit_entry SET summary = 'x' WHERE service = ? AND seq = 2")
                .param(service).update());
        assertBroken(report(service), 2, "CONTENT_ALTERED");
    }

    @Test
    @DisplayName("una scrittura rifiutata non lascia la tabella senza protezione per la stessa sessione")
    void flagDoesNotLeak() {
        String service = uid("chn-flag").toLowerCase();
        append(service, 2);
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            jdbc.sql("SELECT audit_redact(id, 'Membro anonimo', NULL, NULL) FROM audit_entry WHERE service = ? AND seq = 1")
                    .param(service).query(Boolean.class).single();
            // Dopo la funzione controllata, nella stessa transazione, un UPDATE qualsiasi resta vietato.
            assertThatThrownBy(() -> jdbc.sql("UPDATE audit_entry SET summary = 'y' WHERE service = ? AND seq = 2")
                    .param(service).update()).satisfies(t -> assertThat(sqlState(t)).isEqualTo("42501"));
            s.setRollbackOnly();
        });
        assertOk(report(service));
    }
}
