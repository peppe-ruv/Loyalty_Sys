package io.loyaltyhub.insight.infra;

import io.loyaltyhub.common.sql.SqlColumn;
import io.loyaltyhub.common.sql.SqlOrder;
import io.loyaltyhub.common.sql.SqlWhere;
import io.loyaltyhub.insight.domain.DlqEntry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * Voci DLQ (docs/servizi/insight-service.md §2, §3). Inserimento idempotente: un solo record aperto per
 * (evento, consumer) grazie all'indice parziale {@code ux_dlq_entry_open}; ricerca per {@code status, consumer,
 * errorCode}; chiusura (riprocessa/scarta) condizionata allo stato {@code OPEN}.
 */
@Repository
public class DlqRepository {

    /** Colonne ammesse nei filtri e nell'ordinamento di {@link #search}/{@link #count} (regola 19, ADR-042). */
    enum DlqColumn implements SqlColumn {
        STATUS("status"), CONSUMER("consumer"), ERROR_CODE("error_code"), FIRST_SEEN_AT("first_seen_at"), ID("id");

        private final String sql;

        DlqColumn(String sql) {
            this.sql = sql;
        }

        @Override
        public String sql() {
            return sql;
        }
    }

    /**
     * Testo SQL costante dell'elenco (regola 19, docs/18 §3.10 punto 4): vi si accodano solo {@link SqlWhere#sql()},
     * l'ordinamento costante {@link #SEARCH_ORDER} e la pagina legata {@link #SEARCH_PAGE}.
     */
    static final String SEARCH_SELECT = "SELECT * FROM dlq_entry";

    /** Come {@link #SEARCH_SELECT}, per il totale: vi si accoda solo {@link SqlWhere#sql()}. */
    static final String COUNT_SELECT = "SELECT count(*) FROM dlq_entry";

    /** Dalla voce più recente, a parità di istante per id decrescente (come prima del builder). */
    static final String SEARCH_ORDER = SqlOrder.desc(DlqColumn.FIRST_SEEN_AT)
            .by(DlqColumn.ID, SqlOrder.Direction.DESC).sql();

    static final String SEARCH_PAGE = " LIMIT :limit OFFSET :offset";

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public DlqRepository(JdbcClient jdbc, ObjectMapper mapper, Clock clock) {
        this.clock = clock;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Registra la voce; {@code false} se per (evento, consumer) ce n'è già una aperta (record DLQ riletto). */
    public boolean insert(DlqEntry e, Integer dlqPartition, Long dlqOffset) {
        int rows = jdbc.sql("""
                        INSERT INTO dlq_entry
                          (id, event_id, original_topic, original_type, original_family, consumer, error_code,
                           error_class, error_message, error_stack, retryable, attempts, member_id, correlation_id,
                           payload, dlq_partition, dlq_offset, first_seen_at, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? AS jsonb), ?, ?, ?, 'OPEN')
                        ON CONFLICT (event_id, consumer) WHERE status = 'OPEN' DO NOTHING
                        """)
                .params(e.id(), e.eventId(), e.originalTopic(), e.originalType(), e.family(), e.consumer(),
                        e.errorCode(), e.errorClass(), e.errorMessage(), e.errorStack(), e.retryable(), e.attempts(),
                        e.memberId(), e.correlationId(), json(e.payload()), dlqPartition, dlqOffset,
                        Timestamp.from(e.firstSeenAt() == null ? clock.instant() : e.firstSeenAt()))
                .update();
        return rows > 0;
    }

    public Optional<DlqEntry> findById(String id) {
        return jdbc.sql("SELECT * FROM dlq_entry WHERE id = ?").param(id).query(this::map).optional();
    }

    /** Elenco filtrato (docs §3), dalla voce più recente. */
    public List<DlqEntry> search(String status, String consumer, String errorCode, int limit, int offset) {
        SqlWhere where = filters(status, consumer, errorCode);
        return where.bind(jdbc.sql(SEARCH_SELECT + where.sql() + SEARCH_ORDER + SEARCH_PAGE))
                .param("limit", limit)
                .param("offset", offset)
                .query(this::map).list();
    }

    public long count(String status, String consumer, String errorCode) {
        SqlWhere where = filters(status, consumer, errorCode);
        return where.bind(jdbc.sql(COUNT_SELECT + where.sql())).query(Long.class).single();
    }

    /** Voci di un tracciato (per lo stato {@code FAILED} e i nodi DLQ del tracciato). */
    public List<DlqEntry> byCorrelation(String correlationId) {
        return jdbc.sql("SELECT * FROM dlq_entry WHERE correlation_id = ? ORDER BY first_seen_at ASC")
                .param(correlationId).query(this::map).list();
    }

    /** Chiude una voce aperta; {@code false} se nel frattempo non era più {@code OPEN} (doppio clic, altro ADMIN). */
    public boolean resolve(String id, String status, String actor, String note) {
        return jdbc.sql("""
                        UPDATE dlq_entry SET status = ?, resolved_by = ?, resolved_at = now(), resolution_note = ?
                        WHERE id = ? AND status = 'OPEN'
                        """)
                .params(status, actor, note, id).update() > 0;
    }

    /** Voci DLQ viste nell'ultimo intervallo (volumi 1 h / 24 h del topic DLQ nello stato pipeline). */
    public long countWithin(java.time.Duration window) {
        return jdbc.sql("SELECT count(*) FROM dlq_entry WHERE first_seen_at >= now() - make_interval(secs => ?)")
                .param((double) window.toSeconds()).query(Long.class).single();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM dlq_entry").update();
    }

    /**
     * Filtri facoltativi per uguaglianza, ignorati se assenti o vuoti; ogni valore è ripulito una volta sola (spazi
     * esterni tolti, lo stato anche in maiuscolo) e arriva al database solo come parametro.
     */
    static SqlWhere filters(String status, String consumer, String errorCode) {
        String cleanStatus = clean(status);
        return new SqlWhere()
                .eqIfPresent(DlqColumn.STATUS, cleanStatus == null ? null : cleanStatus.toUpperCase())
                .eqIfPresent(DlqColumn.CONSUMER, clean(consumer))
                .eqIfPresent(DlqColumn.ERROR_CODE, clean(errorCode));
    }

    /** Valore senza spazi esterni, oppure {@code null} (nessun filtro) se assente o vuoto. */
    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String json(JsonNode node) {
        return node == null ? "{}" : mapper.writeValueAsString(node);
    }

    private DlqEntry map(ResultSet rs, int n) throws SQLException {
        boolean retryable = rs.getBoolean("retryable");
        Boolean retryableValue = rs.wasNull() ? null : retryable;
        int attempts = rs.getInt("attempts");
        Integer attemptsValue = rs.wasNull() ? null : attempts;
        Timestamp seen = rs.getTimestamp("first_seen_at");
        Timestamp resolved = rs.getTimestamp("resolved_at");
        String payload = rs.getString("payload");
        return new DlqEntry(
                rs.getString("id"), rs.getString("event_id"), rs.getString("original_topic"),
                rs.getString("original_type"), rs.getString("original_family"), rs.getString("consumer"),
                rs.getString("error_code"), rs.getString("error_class"), rs.getString("error_message"),
                rs.getString("error_stack"), retryableValue, attemptsValue, rs.getString("member_id"),
                rs.getString("correlation_id"), payload == null ? null : mapper.readTree(payload),
                seen == null ? null : seen.toInstant(), rs.getString("status"), rs.getString("resolved_by"),
                resolved == null ? null : resolved.toInstant(), rs.getString("resolution_note"));
    }
}
