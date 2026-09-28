package io.loyaltyhub.campaign.infra;

import io.loyaltyhub.common.sql.SqlColumn;
import io.loyaltyhub.common.sql.SqlOrder;
import io.loyaltyhub.common.sql.SqlWhere;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Registro delle valutazioni per la spiegabilità (docs/servizi/campaign-service.md §2). Pulizia > 30 giorni. */
@Repository
public class EvaluationLogRepository {

    /** Colonne ammesse nei filtri e nell'ordinamento di {@link #search} (regola 19, ADR-042). */
    enum EvaluationLogColumn implements SqlColumn {
        MEMBER_ID("member_id"), OUTCOME("outcome"), EVALUATED_AT("evaluated_at"), ACTION_ID("action_id");

        private final String sql;

        EvaluationLogColumn(String sql) {
            this.sql = sql;
        }

        @Override
        public String sql() {
            return sql;
        }
    }

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

    /**
     * Testo SQL costante del registro (regola 19, docs/18 §3.10 punto 4): vi si accodano solo {@link SqlWhere#sql()},
     * l'ordinamento di {@link #searchOrder()} e il limite legato {@code :limit}.
     */
    private static final String SEARCH = """
            SELECT action_id, member_id, action_type, action_time, evaluated_at, outcome, results::text AS results
            FROM evaluation_log
            """;

    /**
     * Valutazioni più recenti prima ({@code GET /v1/evaluations}, docs §3). Filtri facoltativi, ignorati se assenti o
     * vuoti: membro per uguaglianza, esito per uguaglianza (ripulito e in maiuscolo). A parità di {@code evaluated_at}
     * vale {@code action_id} crescente, così {@code limit} taglia sempre le stesse righe; parametri e forma della
     * risposta non cambiano.
     */
    public List<EvaluationRow> search(String memberId, String outcome, int limit) {
        SqlWhere where = new SqlWhere()
                .when(present(memberId), w -> w.eq(EvaluationLogColumn.MEMBER_ID, memberId))
                .when(present(outcome), w -> w.eq(EvaluationLogColumn.OUTCOME, outcome.trim().toUpperCase()));
        return where.bind(jdbc.sql(SEARCH + where.sql() + searchOrder().sql() + " LIMIT :limit"))
                .param("limit", limit)
                .query((rs, n) -> new EvaluationRow(
                        rs.getString("action_id"), rs.getString("member_id"), rs.getString("action_type"),
                        rs.getTimestamp("action_time").toInstant(), rs.getTimestamp("evaluated_at").toInstant(),
                        rs.getString("outcome"), rs.getString("results")))
                .list();
    }

    /** Ordinamento del registro, nuovo a ogni chiamata perché {@link SqlOrder} è mutabile. */
    private static SqlOrder searchOrder() {
        return SqlOrder.desc(EvaluationLogColumn.EVALUATED_AT)
                .by(EvaluationLogColumn.ACTION_ID, SqlOrder.Direction.ASC);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
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
