package io.loyaltyhub.ingestion.infra;

import io.loyaltyhub.ingestion.domain.InboundStatus;
import io.loyaltyhub.ingestion.domain.RejectCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/** Persistenza di {@code inbound_event} (docs/servizi/ingestion-service.md §2, §5). */
@Repository
public class InboundEventRepository {

    private final JdbcClient jdbc;

    public InboundEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Riga del monitor ingressi (docs/servizi/ingestion-service.md §3, BO-26). */
    public record InboundRow(String id, String eventId, String sourceCode, String typeCode, String subject,
                             String memberId, java.time.Instant receivedAt, String status, String rejectCode,
                             String rejectDetail, String correlationId) {
    }

    public java.util.List<InboundRow> search(String status, String source, String type, String memberId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, event_id, source_code, type_code, subject, member_id, received_at,
                       status, reject_code, reject_detail, correlation_id
                FROM inbound_event WHERE 1 = 1
                """);
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim().toUpperCase());
        }
        if (source != null && !source.isBlank()) {
            sql.append(" AND source_code = ?");
            args.add(source.trim());
        }
        if (type != null && !type.isBlank()) {
            sql.append(" AND type_code = ?");
            args.add(type.trim());
        }
        if (memberId != null && !memberId.isBlank()) {
            sql.append(" AND member_id = ?");
            args.add(memberId.trim());
        }
        sql.append(" ORDER BY received_at DESC LIMIT ?");
        args.add(limit);
        return jdbc.sql(sql.toString()).params(args).query(InboundEventRepository::mapRow).list();
    }

    public java.util.Optional<InboundRow> findById(String id) {
        return jdbc.sql("""
                        SELECT id, event_id, source_code, type_code, subject, member_id, received_at,
                               status, reject_code, reject_detail, correlation_id
                        FROM inbound_event WHERE id = ?
                        """)
                .param(id).query(InboundEventRepository::mapRow).optional();
    }

    private static InboundRow mapRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        java.sql.Timestamp ts = rs.getTimestamp("received_at");
        return new InboundRow(rs.getString("id"), rs.getString("event_id"), rs.getString("source_code"),
                rs.getString("type_code"), rs.getString("subject"), rs.getString("member_id"),
                ts == null ? null : ts.toInstant(), rs.getString("status"), rs.getString("reject_code"),
                rs.getString("reject_detail"), rs.getString("correlation_id"));
    }

    /** Esiste già un evento ACCETTATO per questa coppia (fonte, id)? (dedup, docs §5 punto 6). */
    public boolean acceptedExists(String sourceCode, String eventId) {
        Long count = jdbc.sql("SELECT count(*) FROM inbound_event WHERE source_code = ? AND event_id = ? AND status = 'ACCEPTED'")
                .params(sourceCode, eventId).query(Long.class).single();
        return count > 0;
    }

    /**
     * Inserisce l'evento accettato; {@code false} se un altro accettato con stessa (fonte, id) esiste già
     * (indice parziale unico) — gara concorrente trattata come duplicato.
     */
    public boolean insertAccepted(String id, String eventId, String sourceCode, String typeCode, String subject,
                                  String memberId, Instant eventTime, String payloadJson, String correlationId, String origin) {
        int inserted = jdbc.sql("""
                        INSERT INTO inbound_event
                          (id, event_id, source_code, type_code, subject, member_id, event_time,
                           status, payload, correlation_id, origin)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'ACCEPTED', cast(? AS jsonb), ?, ?)
                        ON CONFLICT (source_code, event_id) WHERE status = 'ACCEPTED' DO NOTHING
                        """)
                .params(id, eventId, sourceCode, typeCode, subject, memberId,
                        java.sql.Timestamp.from(eventTime), payloadJson, correlationId, origin)
                .update();
        return inserted == 1;
    }

    /** Registra un esito non accettato (REJECTED / UNMATCHED) — sempre inserito, non soggetto a dedup. */
    public void saveOutcome(String id, String eventId, String sourceCode, String typeCode, String subject,
                            String memberId, Instant eventTime, InboundStatus status, RejectCode rejectCode,
                            String rejectDetail, String payloadJson, String correlationId, String origin) {
        jdbc.sql("""
                        INSERT INTO inbound_event
                          (id, event_id, source_code, type_code, subject, member_id, event_time,
                           status, reject_code, reject_detail, payload, correlation_id, origin)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? AS jsonb), ?, ?)
                        """)
                .params(id, eventId, sourceCode, typeCode, subject, memberId,
                        java.sql.Timestamp.from(eventTime), status.name(),
                        rejectCode == null ? null : rejectCode.name(), rejectDetail,
                        payloadJson, correlationId, origin)
                .update();
    }
}
