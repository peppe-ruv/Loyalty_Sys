package io.loyaltyhub.insight.infra;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Statistiche per topic (docs/servizi/insight-service.md §2): ultimo evento, conteggio, ultimi offset. */
@Repository
public class TopicStatRepository {

    private final JdbcClient jdbc;

    public TopicStatRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Registra un evento osservato su un topic/partizione: incrementa il conteggio e aggiorna l'offset. */
    public void record(String topic, Instant eventTime, int partition, long offset) {
        jdbc.sql("""
                        INSERT INTO topic_stat (topic, last_event_at, count_total, last_offset_by_partition)
                        VALUES (?, ?, 1, jsonb_build_object(?, ?::bigint))
                        ON CONFLICT (topic) DO UPDATE SET
                          last_event_at = GREATEST(topic_stat.last_event_at, EXCLUDED.last_event_at),
                          count_total = topic_stat.count_total + 1,
                          last_offset_by_partition =
                            topic_stat.last_offset_by_partition || jsonb_build_object(?, ?::bigint)
                        """)
                .params(topic, eventTime == null ? null : Timestamp.from(eventTime),
                        String.valueOf(partition), offset, String.valueOf(partition), offset)
                .update();
    }

    public List<TopicStat> findAll() {
        return jdbc.sql("SELECT * FROM topic_stat ORDER BY topic").query(TopicStatRepository::map).list();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM topic_stat").update();
    }

    public record TopicStat(String topic, Instant lastEventAt, long countTotal, String lastOffsetByPartition) {
    }

    private static TopicStat map(ResultSet rs, int n) throws SQLException {
        Timestamp last = rs.getTimestamp("last_event_at");
        return new TopicStat(rs.getString("topic"), last == null ? null : last.toInstant(),
                rs.getLong("count_total"), rs.getString("last_offset_by_partition"));
    }
}
