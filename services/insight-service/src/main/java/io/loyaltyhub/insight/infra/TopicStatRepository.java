package io.loyaltyhub.insight.infra;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Statistiche della pipeline (docs/servizi/insight-service.md §2, §3): per topic ultimo evento, conteggio, ultimi
 * offset e ritardo stimato dell'ultimo record; per servizio l'ultimo fatto prodotto.
 */
@Repository
public class TopicStatRepository {

    private final JdbcClient jdbc;

    public TopicStatRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Registra un record osservato su un topic/partizione: incrementa il conteggio, aggiorna l'offset e il ritardo
     * stimato ({@code lagMs} = arrivo − timestamp del record; {@code null} se il record non ha timestamp).
     */
    public void record(String topic, Instant eventTime, int partition, long offset, Instant receivedAt, Long lagMs) {
        jdbc.sql("""
                        INSERT INTO topic_stat (topic, last_event_at, count_total, last_offset_by_partition,
                                                last_received_at, last_lag_ms)
                        VALUES (?, ?, 1, jsonb_build_object(?, ?::bigint), ?, ?)
                        ON CONFLICT (topic) DO UPDATE SET
                          last_event_at = GREATEST(topic_stat.last_event_at, EXCLUDED.last_event_at),
                          count_total = topic_stat.count_total + 1,
                          last_offset_by_partition =
                            topic_stat.last_offset_by_partition || jsonb_build_object(?, ?::bigint),
                          last_received_at = EXCLUDED.last_received_at,
                          last_lag_ms = coalesce(EXCLUDED.last_lag_ms, topic_stat.last_lag_ms)
                        """)
                .params(topic, eventTime == null ? null : Timestamp.from(eventTime),
                        String.valueOf(partition), offset,
                        receivedAt == null ? null : Timestamp.from(receivedAt), lagMs,
                        String.valueOf(partition), offset)
                .update();
    }

    /** Ultimo fatto prodotto da un servizio (insight §3 «per servizio: ultimo fatto prodotto»). */
    public void recordFact(String service, Instant receivedAt, String shortType, String eventId) {
        jdbc.sql("""
                        INSERT INTO service_stat (service, last_fact_at, last_fact_type, last_event_id, facts_total)
                        VALUES (?, ?, ?, ?, 1)
                        ON CONFLICT (service) DO UPDATE SET
                          last_fact_at = EXCLUDED.last_fact_at,
                          last_fact_type = EXCLUDED.last_fact_type,
                          last_event_id = EXCLUDED.last_event_id,
                          facts_total = service_stat.facts_total + 1
                        """)
                .params(service, Timestamp.from(receivedAt), shortType, eventId)
                .update();
    }

    public List<TopicStat> findAll() {
        return jdbc.sql("SELECT * FROM topic_stat ORDER BY topic").query(TopicStatRepository::map).list();
    }

    public List<ServiceStat> findServices() {
        return jdbc.sql("SELECT * FROM service_stat ORDER BY service").query(TopicStatRepository::mapService).list();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM topic_stat").update();
        jdbc.sql("DELETE FROM service_stat").update();
    }

    public record TopicStat(String topic, Instant lastEventAt, long countTotal, String lastOffsetByPartition,
                            Instant lastReceivedAt, Long lastLagMs) {
    }

    public record ServiceStat(String service, Instant lastFactAt, String lastFactType, String lastEventId,
                              long factsTotal) {
    }

    private static TopicStat map(ResultSet rs, int n) throws SQLException {
        Timestamp last = rs.getTimestamp("last_event_at");
        Timestamp received = rs.getTimestamp("last_received_at");
        long lag = rs.getLong("last_lag_ms");
        Long lagMs = rs.wasNull() ? null : lag;
        return new TopicStat(rs.getString("topic"), last == null ? null : last.toInstant(),
                rs.getLong("count_total"), rs.getString("last_offset_by_partition"),
                received == null ? null : received.toInstant(), lagMs);
    }

    private static ServiceStat mapService(ResultSet rs, int n) throws SQLException {
        return new ServiceStat(rs.getString("service"), rs.getTimestamp("last_fact_at").toInstant(),
                rs.getString("last_fact_type"), rs.getString("last_event_id"), rs.getLong("facts_total"));
    }
}
