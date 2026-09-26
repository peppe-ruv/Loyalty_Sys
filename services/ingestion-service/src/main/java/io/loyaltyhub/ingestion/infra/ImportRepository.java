package io.loyaltyhub.ingestion.infra;

import io.loyaltyhub.common.sql.SqlColumn;
import io.loyaltyhub.common.sql.SqlWhere;
import io.loyaltyhub.ingestion.domain.ImportJob;
import io.loyaltyhub.ingestion.domain.ImportRowResult;
import io.loyaltyhub.ingestion.domain.ItemOutcome;
import io.loyaltyhub.ingestion.domain.OutcomeCounts;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Persistenza degli import file (tabelle {@code import_job} e {@code import_row}, F2-ING-02, BO-32). SQL costante o
 * composto da {@link SqlWhere} con colonne da enum (regola 19).
 */
@Repository
public class ImportRepository {

    /** Colonne filtrabili (regola 19: solo da enum). */
    enum ImportColumn implements SqlColumn {
        STATUS("status"),
        IMPORT_ID("r.import_id"),
        OUTCOME("r.outcome");

        private final String sql;

        ImportColumn(String sql) {
            this.sql = sql;
        }

        @Override
        public String sql() {
            return sql;
        }
    }

    /** Lavoro preso in carico dal lavoratore: ciò che serve per elaborarlo dal punto di ripresa. */
    public record Claimed(String id, String format, String content, String defaultSource, int rowsDone, int attempts,
                          String createdBy) {
    }

    /** Nuovo lavoro in coda. */
    public record NewJob(String id, String kind, String format, String fileName, int sizeBytes, String sha256,
                         String defaultSource, int rowsTotal, String content, String idempotencyKey, String createdBy,
                         Instant createdAt) {
    }

    private static final String JOB_COLUMNS = """
            id, kind, format, file_name, size_bytes, sha256, default_source, status, rows_total, rows_done,
            accepted, duplicate, rejected, unmatched, invalid, attempts, error_detail, created_by, created_at,
            started_at, finished_at
            """;

    private final JdbcClient jdbc;

    public ImportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ================= lavori =================

    /**
     * Inserisce il lavoro in coda; {@code false} se lo stesso autore ha già usato la stessa {@code Idempotency-Key}
     * (nessun doppione, anche con due richieste in parallelo: la seconda attende la prima sull'indice unico).
     */
    public boolean insert(NewJob j) {
        return jdbc.sql("""
                        INSERT INTO import_job (id, kind, format, file_name, size_bytes, sha256, default_source, status,
                          rows_total, content, idempotency_key, created_by, created_at)
                        VALUES (:id, :kind, :format, :fileName, :size, :sha, :source, 'QUEUED', :rows, :content, :key,
                          :createdBy, :createdAt)
                        ON CONFLICT (created_by, idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING
                        """)
                .param("id", j.id()).param("kind", j.kind()).param("format", j.format())
                .param("fileName", j.fileName()).param("size", j.sizeBytes()).param("sha", j.sha256())
                .param("source", j.defaultSource()).param("rows", j.rowsTotal()).param("content", j.content())
                .param("key", j.idempotencyKey()).param("createdBy", j.createdBy())
                .param("createdAt", Timestamp.from(j.createdAt()))
                .update() == 1;
    }

    /** Lavoro dello stesso autore con questa {@code Idempotency-Key}. */
    public Optional<ImportJob> findByIdempotencyKey(String createdBy, String key) {
        return jdbc.sql("SELECT " + JOB_COLUMNS + " FROM import_job WHERE created_by = :createdBy AND idempotency_key = :key")
                .param("createdBy", createdBy).param("key", key).query(ImportRepository::mapJob).optional();
    }

    public Optional<ImportJob> findById(String id) {
        return jdbc.sql("SELECT " + JOB_COLUMNS + " FROM import_job WHERE id = :id")
                .param("id", id).query(ImportRepository::mapJob).optional();
    }

    /** Pagina dei lavori, più recenti prima; {@code status} facoltativo. */
    public List<ImportJob> page(String status, int limit, int offset) {
        SqlWhere where = new SqlWhere().eqIfPresent(ImportColumn.STATUS, status);
        return where.bind(jdbc.sql("SELECT " + JOB_COLUMNS + " FROM import_job" + where.sql()
                        + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset"))
                .param("limit", limit).param("offset", offset)
                .query(ImportRepository::mapJob).list();
    }

    public long count(String status) {
        SqlWhere where = new SqlWhere().eqIfPresent(ImportColumn.STATUS, status);
        return where.bind(jdbc.sql("SELECT count(*) FROM import_job" + where.sql())).query(Long.class).single();
    }

    /**
     * Prende in carico il lavoro più vecchio in coda, oppure un {@code RUNNING} fermo da prima di {@code staleBefore}
     * (lavoratore arrestato): stato {@code RUNNING}, tentativi +1. {@code SKIP LOCKED}: due istanze non prendono lo
     * stesso lavoro.
     */
    public Optional<Claimed> claim(Instant now, Instant staleBefore) {
        return jdbc.sql("""
                        UPDATE import_job SET status = 'RUNNING', attempts = attempts + 1,
                          started_at = coalesce(started_at, :now), heartbeat_at = :now
                        WHERE id = (
                          SELECT id FROM import_job
                          WHERE status = 'QUEUED' OR (status = 'RUNNING' AND heartbeat_at < :stale)
                          ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED)
                        RETURNING id, format, content, default_source, rows_done, attempts, created_by
                        """)
                .param("now", Timestamp.from(now)).param("stale", Timestamp.from(staleBefore))
                .query((rs, n) -> new Claimed(rs.getString("id"), rs.getString("format"), rs.getString("content"),
                        rs.getString("default_source"), rs.getInt("rows_done"), rs.getInt("attempts"),
                        rs.getString("created_by")))
                .optional();
    }

    /**
     * Avanza di una riga con il suo esito, solo se il lavoro è ancora {@code RUNNING} e al punto atteso
     * ({@code rows_done = expectedDone}): un secondo lavoratore sullo stesso lavoro trova 0 righe aggiornate e si ferma.
     */
    public boolean advance(String id, int expectedDone, ItemOutcome outcome, Instant now) {
        return jdbc.sql("""
                        UPDATE import_job SET rows_done = rows_done + 1, heartbeat_at = :now,
                          accepted  = accepted  + CASE WHEN :outcome = 'ACCEPTED'  THEN 1 ELSE 0 END,
                          duplicate = duplicate + CASE WHEN :outcome = 'DUPLICATE' THEN 1 ELSE 0 END,
                          rejected  = rejected  + CASE WHEN :outcome = 'REJECTED'  THEN 1 ELSE 0 END,
                          unmatched = unmatched + CASE WHEN :outcome = 'UNMATCHED' THEN 1 ELSE 0 END,
                          invalid   = invalid   + CASE WHEN :outcome = 'INVALID'   THEN 1 ELSE 0 END
                        WHERE id = :id AND status = 'RUNNING' AND rows_done = :expected
                        """)
                .param("now", Timestamp.from(now)).param("outcome", outcome.name())
                .param("id", id).param("expected", expectedDone)
                .update() == 1;
    }

    /** Fine elaborazione: {@code DONE}, file svuotato. {@code false} se il lavoro non era più in lavorazione. */
    public boolean finish(String id, Instant now) {
        return jdbc.sql("""
                        UPDATE import_job SET status = 'DONE', finished_at = :now, heartbeat_at = :now, content = NULL
                        WHERE id = :id AND status = 'RUNNING'
                        """)
                .param("now", Timestamp.from(now)).param("id", id).update() == 1;
    }

    /** Lavoro non completabile: {@code FAILED} con il motivo, file svuotato. */
    public boolean fail(String id, String detail, Instant now) {
        return jdbc.sql("""
                        UPDATE import_job SET status = 'FAILED', error_detail = :detail, finished_at = :now,
                          heartbeat_at = :now, content = NULL
                        WHERE id = :id AND status IN ('QUEUED', 'RUNNING')
                        """)
                .param("detail", detail).param("now", Timestamp.from(now)).param("id", id).update() == 1;
    }

    /** Pulizia (docs/servizi/ingestion-service.md §2): lavori conclusi prima di {@code before}, righe comprese. */
    public int deleteFinishedBefore(Instant before) {
        return jdbc.sql("DELETE FROM import_job WHERE status IN ('DONE', 'FAILED') AND finished_at < :before")
                .param("before", Timestamp.from(before)).update();
    }

    /** Reset demo (docs/06 §10). */
    public int deleteAll() {
        return jdbc.sql("DELETE FROM import_job").update();
    }

    /**
     * Lavoro concluso dello storico demo (docs/10 §8.2): nessun file, contatori già calcolati dal seeder. Solo
     * {@code DemoSeeder}.
     */
    public void insertHistory(String id, String format, String fileName, int sizeBytes, String sha256,
                              String defaultSource, OutcomeCounts counts, String createdBy, Instant createdAt,
                              Instant finishedAt) {
        jdbc.sql("""
                        INSERT INTO import_job (id, kind, format, file_name, size_bytes, sha256, default_source, status,
                          rows_total, rows_done, accepted, duplicate, rejected, unmatched, invalid, attempts, created_by,
                          created_at, started_at, finished_at, heartbeat_at)
                        VALUES (:id, 'EVENTS', :format, :fileName, :size, :sha, :source, 'DONE', :total, :total,
                          :accepted, :duplicate, :rejected, :unmatched, :invalid, 1, :createdBy, :createdAt, :createdAt,
                          :finishedAt, :finishedAt)
                        """)
                .param("id", id).param("format", format).param("fileName", fileName).param("size", sizeBytes)
                .param("sha", sha256).param("source", defaultSource).param("total", counts.total())
                .param("accepted", counts.accepted()).param("duplicate", counts.duplicate())
                .param("rejected", counts.rejected()).param("unmatched", counts.unmatched())
                .param("invalid", counts.invalid()).param("createdBy", createdBy)
                .param("createdAt", Timestamp.from(createdAt)).param("finishedAt", Timestamp.from(finishedAt))
                .update();
    }

    /** Riga del monitor ingressi citata dallo storico demo degli import. */
    public record InboundRef(String id, String rejectCode, String detail, Instant receivedAt) {
    }

    /** Righe di {@code inbound_event} con questo id evento ed esito, dalla più vecchia (solo {@code DemoSeeder}). */
    public List<InboundRef> inboundByEventAndStatus(String eventId, String status) {
        return jdbc.sql("""
                        SELECT id, reject_code, reject_detail, received_at FROM inbound_event
                        WHERE event_id = :eventId AND status = :status ORDER BY received_at, id
                        """)
                .param("eventId", eventId).param("status", status)
                .query((rs, n) -> new InboundRef(rs.getString("id"), rs.getString("reject_code"),
                        rs.getString("reject_detail"), instant(rs, "received_at")))
                .list();
    }

    // ================= righe =================

    /**
     * Riga non accettata del rapporto. {@code detail} non contiene mai il soggetto: per {@code UNMATCHED} resta
     * {@code null} e si legge da {@code inbound_event.reject_detail}, che l'anonimizzazione ripulisce.
     */
    public record NewRow(int rowNumber, Integer lineNumber, String eventId, ItemOutcome outcome, String rejectCode,
                         String detail, String inboundEventId) {
    }

    public void insertRow(String importId, NewRow row) {
        jdbc.sql("""
                        INSERT INTO import_row (import_id, row_number, line_number, event_id, outcome, reject_code, detail,
                          inbound_event_id)
                        VALUES (:importId, :row, :line, :eventId, :outcome, :rejectCode, :detail, :inboundId)
                        """)
                .param("importId", importId).param("row", row.rowNumber()).param("line", row.lineNumber())
                .param("eventId", row.eventId()).param("outcome", row.outcome().name())
                .param("rejectCode", row.rejectCode()).param("detail", row.detail())
                .param("inboundId", row.inboundEventId())
                .update();
    }

    /**
     * Dettaglio dei non abbinati dalla riga del monitor (con il soggetto finché l'anonimizzazione non lo toglie), degli
     * altri dal rapporto; {@code current_status} = esito attuale nel monitor.
     */
    private static final String ROW_SELECT = """
            SELECT r.row_number, r.line_number, r.event_id, r.outcome, r.reject_code,
              CASE WHEN r.outcome = 'UNMATCHED' THEN e.reject_detail ELSE r.detail END AS detail,
              r.inbound_event_id, e.status AS current_status
            FROM import_row r LEFT JOIN inbound_event e ON e.id = r.inbound_event_id
            """;

    /** Righe non accettate del lavoro, in ordine di riga; {@code outcome} facoltativo. */
    public List<ImportRowResult> rows(String importId, String outcome, int limit, int offset) {
        SqlWhere where = rowFilter(importId, outcome);
        return where.bind(jdbc.sql(ROW_SELECT + where.sql() + " ORDER BY r.row_number LIMIT :limit OFFSET :offset"))
                .param("limit", limit).param("offset", offset)
                .query(ImportRepository::mapRow).list();
    }

    /** Tutte le righe non accettate del lavoro, in ordine di riga, una alla volta (rapporto CSV, senza elenco in memoria). */
    public void forEachRow(String importId, Consumer<ImportRowResult> action) {
        SqlWhere where = rowFilter(importId, null);
        RowCallbackHandler handler = rs -> action.accept(mapRow(rs, 0));
        where.bind(jdbc.sql(ROW_SELECT + where.sql() + " ORDER BY r.row_number")).query(handler);
    }

    public long countRows(String importId, String outcome) {
        SqlWhere where = rowFilter(importId, outcome);
        return where.bind(jdbc.sql("SELECT count(*) FROM import_row r" + where.sql())).query(Long.class).single();
    }

    private static SqlWhere rowFilter(String importId, String outcome) {
        return new SqlWhere().eq(ImportColumn.IMPORT_ID, importId).eqIfPresent(ImportColumn.OUTCOME, outcome);
    }

    /** Riga del rapporto il cui ingresso nel monitor è ancora {@code UNMATCHED}. */
    public record OpenUnmatched(int rowNumber, String inboundEventId) {
    }

    /**
     * Righe di questo import il cui ingresso è ancora {@code UNMATCHED}, dopo la riga {@code afterRow}, in ordine, al
     * più {@code limit} (per «Riprova non abbinati» a blocchi).
     */
    public List<OpenUnmatched> openUnmatched(String importId, int afterRow, int limit) {
        return jdbc.sql("""
                        SELECT r.row_number, r.inbound_event_id
                        FROM import_row r JOIN inbound_event e ON e.id = r.inbound_event_id
                        WHERE r.import_id = :importId AND e.status = 'UNMATCHED' AND r.row_number > :afterRow
                        ORDER BY r.row_number LIMIT :limit
                        """)
                .param("importId", importId).param("afterRow", afterRow).param("limit", limit)
                .query((rs, n) -> new OpenUnmatched(rs.getInt("row_number"), rs.getString("inbound_event_id")))
                .list();
    }

    public long countOpenUnmatched(String importId) {
        return jdbc.sql("""
                        SELECT count(*) FROM import_row r JOIN inbound_event e ON e.id = r.inbound_event_id
                        WHERE r.import_id = :importId AND e.status = 'UNMATCHED'
                        """)
                .param("importId", importId).query(Long.class).single();
    }

    // ================= mapping =================

    private static ImportJob mapJob(ResultSet rs, int n) throws SQLException {
        return new ImportJob(rs.getString("id"), rs.getString("kind"), rs.getString("format"), rs.getString("file_name"),
                rs.getInt("size_bytes"), rs.getString("sha256"), rs.getString("default_source"),
                ImportJob.Status.valueOf(rs.getString("status")), rs.getInt("rows_total"), rs.getInt("rows_done"),
                new OutcomeCounts(rs.getInt("accepted"), rs.getInt("duplicate"), rs.getInt("rejected"),
                        rs.getInt("unmatched"), rs.getInt("invalid")),
                rs.getInt("attempts"), rs.getString("error_detail"), rs.getString("created_by"),
                instant(rs, "created_at"), instant(rs, "started_at"), instant(rs, "finished_at"));
    }

    private static ImportRowResult mapRow(ResultSet rs, int n) throws SQLException {
        return new ImportRowResult(rs.getInt("row_number"), rs.getObject("line_number", Integer.class),
                rs.getString("event_id"),
                ItemOutcome.valueOf(rs.getString("outcome")), rs.getString("reject_code"), rs.getString("detail"),
                rs.getString("inbound_event_id"), rs.getString("current_status"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
