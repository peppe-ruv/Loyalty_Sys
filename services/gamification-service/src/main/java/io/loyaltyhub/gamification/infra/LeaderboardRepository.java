package io.loyaltyhub.gamification.infra;

import io.loyaltyhub.gamification.domain.Leaderboard;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Classifiche e punteggi (docs/servizi/gamification-service.md §2). Il ranking esclude i membri non {@code ACTIVE};
 * a parità di punteggio vince chi l'ha raggiunto prima (docs/03 §8).
 */
@Repository
public class LeaderboardRepository {

    private static final String COLUMNS = "id, code, name, metric, action_types, period, top_n, status";
    /** Mese, poi edizione, poi sempre: la classifica più "viva" per prima (PT-10). */
    private static final String ORDER = " ORDER BY CASE period WHEN 'MONTH' THEN 0 WHEN 'EDITION' THEN 1 ELSE 2 END, code";

    public record Ranked(int rank, String memberId, String nickname, long score, Instant reachedAt) {
    }

    private final JdbcClient jdbc;

    public LeaderboardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Leaderboard> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM leaderboard" + ORDER).query(LeaderboardRepository::map).list();
    }

    public List<Leaderboard> findActive() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM leaderboard WHERE status = 'ACTIVE'" + ORDER)
                .query(LeaderboardRepository::map).list();
    }

    public Optional<Leaderboard> find(String idOrCode) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM leaderboard WHERE id = ? OR code = ?").params(idOrCode, idOrCode)
                .query(LeaderboardRepository::map).optional();
    }

    public void insert(Leaderboard l) {
        jdbc.sql("""
                        INSERT INTO leaderboard (id, code, name, metric, action_types, period, top_n, status)
                        VALUES (?, ?, ?, ?, ?::text[], ?, ?, ?)
                        """)
                .params(l.id(), l.code(), l.name(), l.metric(), TextArrays.literal(l.actionTypes()), l.period(), l.topN(), l.status())
                .update();
    }

    public void update(Leaderboard l) {
        jdbc.sql("""
                        UPDATE leaderboard SET name = ?, metric = ?, action_types = ?::text[], period = ?, top_n = ?, status = ?
                        WHERE id = ?
                        """)
                .params(l.name(), l.metric(), TextArrays.literal(l.actionTypes()), l.period(), l.topN(), l.status(), l.id())
                .update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM leaderboard_score").update();
        jdbc.sql("DELETE FROM leaderboard").update();
    }

    /** Somma al punteggio; {@code reached_at} si aggiorna a ogni aumento (parimerito: vince chi è arrivato prima). */
    public void add(String leaderboardId, String periodKey, String memberId, long delta, Instant at) {
        jdbc.sql("""
                        INSERT INTO leaderboard_score (leaderboard_id, period_key, member_id, score, reached_at)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (leaderboard_id, period_key, member_id)
                        DO UPDATE SET score = leaderboard_score.score + excluded.score, reached_at = excluded.reached_at
                        """)
                .params(leaderboardId, periodKey, memberId, delta, Timestamp.from(at)).update();
    }

    /** Classifica del periodo, solo membri attivi; {@code limit} ≤ 0 → tutti. */
    public List<Ranked> ranking(String leaderboardId, String periodKey, int limit) {
        String sql = """
                SELECT row_number() OVER (ORDER BY s.score DESC, s.reached_at, s.member_id) AS rank, s.member_id,
                  m.nickname, s.score, s.reached_at
                FROM leaderboard_score s
                JOIN gamification_member_snapshot m ON m.member_id = s.member_id AND m.status = 'ACTIVE'
                WHERE s.leaderboard_id = ? AND s.period_key = ? AND s.score > 0
                ORDER BY rank""" + (limit > 0 ? " LIMIT " + limit : "");
        return jdbc.sql(sql).params(leaderboardId, periodKey)
                .query((rs, n) -> new Ranked(rs.getInt("rank"), rs.getString("member_id"), rs.getString("nickname"),
                        rs.getLong("score"), ContestRepository.inst(rs, "reached_at")))
                .list();
    }

    /** Periodi con punteggi, dal più recente (selettore di BO-16). */
    public List<String> periods(String leaderboardId) {
        return jdbc.sql("SELECT DISTINCT period_key FROM leaderboard_score WHERE leaderboard_id = ? ORDER BY period_key DESC")
                .param(leaderboardId).query(String.class).list();
    }

    private static Leaderboard map(ResultSet rs, int n) throws SQLException {
        return new Leaderboard(rs.getString("id"), rs.getString("code"), rs.getString("name"), rs.getString("metric"),
                TextArrays.toList(rs.getArray("action_types")), rs.getString("period"), rs.getInt("top_n"), rs.getString("status"));
    }
}
