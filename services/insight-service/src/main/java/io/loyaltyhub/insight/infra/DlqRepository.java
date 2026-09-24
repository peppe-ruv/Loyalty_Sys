package io.loyaltyhub.insight.infra;

import io.loyaltyhub.insight.domain.DlqEntry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Voci DLQ (docs/servizi/insight-service.md §2, §3). Inserimento idempotente: un solo record aperto per
 * (evento, consumer) grazie all'indice parziale {@code ux_dlq_entry_open}; ricerca per {@code status, consumer,
 * errorCode}; chiusura (riprocessa/scarta) condizionata allo stato {@code OPEN}.
 */
@Repository
public class DlqRepository {

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
        StringBuilder sql = new StringBuilder("SELECT * FROM dlq_entry WHERE 1=1");
        List<Object> args = filters(sql, status, consumer, errorCode);
        sql.append(" ORDER BY first_seen_at DESC, id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.sql(sql.toString()).params(args).query(this::map).list();
    }

    public long count(String status, String consumer, String errorCode) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM dlq_entry WHERE 1=1");
        List<Object> args = filters(sql, status, consumer, errorCode);
        return jdbc.sql(sql.toString()).params(args).query(Long.class).single();
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

    public void deleteAll() {
        jdbc.sql("DELETE FROM dlq_entry").update();
    }

    private static List<Object> filters(StringBuilder sql, String status, String consumer, String errorCode) {
        List<Object> args = new ArrayList<>();
        appendEq(sql, args, "status", status == null ? null : status.trim().toUpperCase());
        appendEq(sql, args, "consumer", consumer);
        appendEq(sql, args, "error_code", errorCode);
        return args;
    }

    private static void appendEq(StringBuilder sql, List<Object> args, String col, String value) {
        if (value != null && !value.isBlank()) {
            sql.append(" AND ").append(col).append(" = ?");
            args.add(value.trim());
        }
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
