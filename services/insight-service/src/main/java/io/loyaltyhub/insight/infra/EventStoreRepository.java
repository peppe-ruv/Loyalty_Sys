package io.loyaltyhub.insight.infra;

import io.loyaltyhub.insight.domain.StoredEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Event store (docs/servizi/insight-service.md §2, §5). Inserimento idempotente su {@code event_id}
 * ({@code ON CONFLICT DO NOTHING}); la retention taglia per età e per numero di righe.
 */
@Repository
public class EventStoreRepository {

    private final JdbcClient jdbc;

    public EventStoreRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserisce l'evento; restituisce {@code true} se era nuovo (un duplicato non conta due volte). */
    public boolean insert(StoredEvent e) {
        int rows = jdbc.sql("""
                        INSERT INTO event_store
                          (event_id, topic, family, type, short_type, source, member_id, correlation_id,
                           causation_id, hop, actor, error_code, event_time, kafka_partition, kafka_offset, payload)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? AS jsonb))
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .params(e.eventId(), e.topic(), e.family(), e.type(), e.shortType(), e.source(), e.memberId(),
                        e.correlationId(), e.causationId(), e.hop(), e.actor(), e.errorCode(),
                        e.eventTime() == null ? null : Timestamp.from(e.eventTime()),
                        e.partition(), e.offset(), e.payloadJson() == null ? "{}" : e.payloadJson())
                .update();
        return rows > 0;
    }

    public Optional<StoredEvent> findById(String eventId) {
        return jdbc.sql("SELECT * FROM event_store WHERE event_id = ?").param(eventId)
                .query(EventStoreRepository::map).optional();
    }

    /** Ricerca con filtri (docs/servizi/insight-service.md §3, {@code GET /v1/events}), dal più recente, a pagine. */
    public List<StoredEvent> search(String topic, String family, String type, String memberId,
                                    String correlationId, String source, Instant from, Instant to,
                                    String q, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        String where = where(args, topic, family, type, memberId, correlationId, source, from, to, q);
        args.add(limit);
        args.add(offset);
        return jdbc.sql("SELECT * FROM event_store" + where + " ORDER BY received_at DESC LIMIT ? OFFSET ?")
                .params(args).query(EventStoreRepository::map).list();
    }

    /** Totale degli eventi che passano i filtri di {@link #search} ({@code page.totalItems}, docs/06 §2). */
    public long count(String topic, String family, String type, String memberId, String correlationId,
                      String source, Instant from, Instant to, String q) {
        List<Object> args = new ArrayList<>();
        String where = where(args, topic, family, type, memberId, correlationId, source, from, to, q);
        return jdbc.sql("SELECT count(*) FROM event_store" + where).params(args).query(Long.class).single();
    }

    private static String where(List<Object> args, String topic, String family, String type, String memberId,
                                String correlationId, String source, Instant from, Instant to, String q) {
        StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        appendEq(sql, args, "topic", topic);
        appendEq(sql, args, "family", family == null ? null : family.toUpperCase());
        appendEq(sql, args, "short_type", type);
        appendEq(sql, args, "member_id", memberId);
        appendEq(sql, args, "correlation_id", correlationId);
        appendEq(sql, args, "source", source);
        if (from != null) {
            sql.append(" AND received_at >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND received_at <= ?");
            args.add(Timestamp.from(to));
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND payload::text ILIKE ?");
            args.add("%" + q.trim() + "%");
        }
        return sql.toString();
    }

    private static void appendEq(StringBuilder sql, List<Object> args, String col, String value) {
        if (value != null && !value.isBlank()) {
            sql.append(" AND ").append(col).append(" = ?");
            args.add(value.trim());
        }
    }

    /** Tutti gli eventi di un tracciato, in ordine di osservazione (per costruire l'albero). */
    public List<StoredEvent> byCorrelation(String correlationId) {
        return jdbc.sql("SELECT * FROM event_store WHERE correlation_id = ? ORDER BY received_at ASC")
                .param(correlationId).query(EventStoreRepository::map).list();
    }

    /** Gli ultimi correlationId osservati (uno per tracciato), opzionalmente per membro e intervallo, a pagine. */
    public List<String> recentCorrelationIds(String memberId, Instant from, Instant to, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        String where = correlationWhere(args, memberId, from, to);
        args.add(limit);
        args.add(offset);
        return jdbc.sql("SELECT correlation_id FROM event_store" + where
                        + " GROUP BY correlation_id ORDER BY max(received_at) DESC LIMIT ? OFFSET ?")
                .params(args).query(String.class).list();
    }

    /** Numero di tracciati distinti che passano i filtri di {@link #recentCorrelationIds}. */
    public long countCorrelationIds(String memberId, Instant from, Instant to) {
        List<Object> args = new ArrayList<>();
        String where = correlationWhere(args, memberId, from, to);
        return jdbc.sql("SELECT count(DISTINCT correlation_id) FROM event_store" + where)
                .params(args).query(Long.class).single();
    }

    /**
     * Eventi arrivati per topic nell'ultimo intervallo (volumi 1 h / 24 h dello stato pipeline, insight §3). La finestra
     * è calcolata dal database, come {@code received_at}.
     */
    public Map<String, Long> countByTopicWithin(java.time.Duration window) {
        Map<String, Long> out = new HashMap<>();
        jdbc.sql("""
                        SELECT topic, count(*) AS n FROM event_store
                        WHERE received_at >= now() - make_interval(secs => ?) GROUP BY topic
                        """)
                .param((double) window.toSeconds())
                .query((rs, n) -> out.put(rs.getString("topic"), rs.getLong("n")))
                .list();
        return out;
    }

    private static String correlationWhere(List<Object> args, String memberId, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder(" WHERE correlation_id IS NOT NULL");
        if (memberId != null && !memberId.isBlank()) {
            sql.append(" AND member_id = ?");
            args.add(memberId.trim());
        }
        if (from != null) {
            sql.append(" AND received_at >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND received_at <= ?");
            args.add(Timestamp.from(to));
        }
        return sql.toString();
    }

    /** Retention per età (docs §5): elimina gli eventi più vecchi di N giorni. */
    public int deleteOlderThan(int days) {
        return jdbc.sql("DELETE FROM event_store WHERE received_at < now() - make_interval(days => ?)")
                .param(days).update();
    }

    /** Retention per numero (docs §5): tiene le {@code maxRows} righe più recenti. */
    public int trimToMaxRows(int maxRows) {
        return jdbc.sql("""
                        DELETE FROM event_store
                        WHERE event_id IN (
                          SELECT event_id FROM event_store
                          ORDER BY received_at DESC
                          OFFSET ?
                        )
                        """)
                .param(maxRows).update();
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM event_store").query(Long.class).single();
    }

    /** Membri distinti che compaiono con un dato tipo evento (es. {@code member.registered}). */
    public long distinctMembers(String shortType) {
        return jdbc.sql("""
                        SELECT count(DISTINCT member_id) FROM event_store
                        WHERE short_type = ? AND member_id IS NOT NULL
                        """)
                .param(shortType).query(Long.class).single();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM event_store").update();
    }

    private static StoredEvent map(ResultSet rs, int n) throws SQLException {
        int hopValue = rs.getInt("hop");
        Integer hop = rs.wasNull() ? null : hopValue; // wasNull() vale sull'ultima colonna letta: subito dopo getInt
        Timestamp eventTime = rs.getTimestamp("event_time");
        Timestamp receivedAt = rs.getTimestamp("received_at");
        return new StoredEvent(
                rs.getString("event_id"), rs.getString("topic"), rs.getString("family"), rs.getString("type"),
                rs.getString("short_type"), rs.getString("source"), rs.getString("member_id"),
                rs.getString("correlation_id"), rs.getString("causation_id"), hop,
                rs.getString("actor"), rs.getString("error_code"),
                eventTime == null ? null : eventTime.toInstant(),
                receivedAt == null ? Instant.EPOCH : receivedAt.toInstant(),
                rs.getInt("kafka_partition"), rs.getLong("kafka_offset"), rs.getString("payload"));
    }
}
