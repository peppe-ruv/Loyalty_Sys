package io.loyaltyhub.campaign.infra;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Registro delle valutazioni per la spiegabilità (docs/servizi/campaign-service.md §2). Pulizia > 30 giorni. */
@Repository
public class EvaluationLogRepository {

    private final JdbcClient jdbc;

    public EvaluationLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserisce la valutazione; idempotente su {@code action_id} (i replay non duplicano). */
    public void save(String actionId, String memberId, String actionType, Instant actionTime,
                     String correlationId, String outcome, String resultsJson) {
        jdbc.sql("""
                        INSERT INTO evaluation_log
                          (action_id, member_id, action_type, action_time, correlation_id, outcome, results)
                        VALUES (?, ?, ?, ?, ?, ?, cast(? AS jsonb))
                        ON CONFLICT (action_id) DO NOTHING
                        """)
                .params(actionId, memberId, actionType, java.sql.Timestamp.from(actionTime),
                        correlationId, outcome, resultsJson)
                .update();
    }

    /** Reset demo (docs/06 §10). */
    public void deleteAll() {
        jdbc.sql("DELETE FROM evaluation_log").update();
    }

    public Optional<String> findResults(String actionId) {
        return jdbc.sql("SELECT results::text FROM evaluation_log WHERE action_id = ?")
                .param(actionId).query(String.class).optional();
    }

    public List<EvaluationRow> search(String memberId, String outcome, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT action_id, member_id, action_type, action_time, evaluated_at, outcome, results::text AS results
                FROM evaluation_log WHERE 1 = 1
                """);
        List<Object> args = new java.util.ArrayList<>();
        if (memberId != null && !memberId.isBlank()) {
            sql.append(" AND member_id = ?");
            args.add(memberId);
        }
        if (outcome != null && !outcome.isBlank()) {
            sql.append(" AND outcome = ?");
            args.add(outcome.trim().toUpperCase());
        }
        sql.append(" ORDER BY evaluated_at DESC LIMIT ?");
        args.add(limit);
        return jdbc.sql(sql.toString()).params(args)
                .query((rs, n) -> new EvaluationRow(
                        rs.getString("action_id"), rs.getString("member_id"), rs.getString("action_type"),
                        rs.getTimestamp("action_time").toInstant(), rs.getTimestamp("evaluated_at").toInstant(),
                        rs.getString("outcome"), rs.getString("results")))
                .list();
    }

    /**
     * Serie giornaliera per una campagna (docs §3, {@code GET /{id}/stats}): per ogni giorno, il numero di
     * attivazioni (result con {@code matched=true} per quel {@code campaignCode}) e i punti decisi
     * (somma degli {@code amount} degli effetti). Da {@code action_time}, negli ultimi {@code from..}.
     */
    public List<DailyStat> dailyForCampaign(String campaignCode, Instant from) {
        return jdbc.sql("""
                        SELECT date(action_time) AS day,
                               count(*) AS matches,
                               coalesce(sum((
                                   SELECT coalesce(sum((e->>'amount')::bigint), 0)
                                   FROM jsonb_array_elements(elem->'effects') e
                                   WHERE e->>'amount' IS NOT NULL
                               )), 0) AS points
                        FROM evaluation_log, jsonb_array_elements(results) elem
                        WHERE elem->>'campaignCode' = ?
                          AND (elem->>'matched')::boolean = true
                          AND action_time >= ?
                        GROUP BY day
                        ORDER BY day
                        """)
                .params(campaignCode, java.sql.Timestamp.from(from))
                .query((rs, n) -> new DailyStat(
                        rs.getObject("day", java.time.LocalDate.class), rs.getLong("matches"), rs.getLong("points")))
                .list();
    }

    public record EvaluationRow(String actionId, String memberId, String actionType, Instant actionTime,
                                Instant evaluatedAt, String outcome, String resultsJson) {
    }

    public record DailyStat(java.time.LocalDate day, long matches, long points) {
    }
}
