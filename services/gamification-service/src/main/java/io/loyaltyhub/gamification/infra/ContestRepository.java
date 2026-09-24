package io.loyaltyhub.gamification.infra;

import io.loyaltyhub.common.approval.ApprovalStatus;
import io.loyaltyhub.gamification.domain.Contest;
import io.loyaltyhub.gamification.domain.Prize;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Concorsi e montepremi (docs/servizi/gamification-service.md §2). */
@Repository
public class ContestRepository {

    private static final String COLUMNS = """
            id, code, name, description, rules_text, mechanic, start_at, end_at, free_play_daily,
            max_plays_per_member_per_day, max_wins_per_member, distribution, seed, instants_generated_at, status,
            version, created_by, updated_at""";
    private static final String PRIZE_COLUMNS = """
            id, contest_id, code, name, type, points, reward_code, quantity_total, quantity_remaining, image_url,
            wheel_color, sort_order""";

    private final JdbcClient jdbc;

    public ContestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Contest> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM contest ORDER BY start_at DESC, code").query(ContestRepository::map).list();
    }

    public List<Contest> findByStatus(ApprovalStatus status) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM contest WHERE status = ? ORDER BY end_at, code")
                .param(status.name()).query(ContestRepository::map).list();
    }

    public Optional<Contest> find(String idOrCode) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM contest WHERE id = ? OR code = ?").params(idOrCode, idOrCode)
                .query(ContestRepository::map).optional();
    }

    /** Riga bloccata: generazione istanti, transizioni e modifiche si serializzano sul concorso. */
    public Optional<Contest> lock(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM contest WHERE id = ? FOR UPDATE").param(id)
                .query(ContestRepository::map).optional();
    }

    public void insert(Contest c) {
        jdbc.sql("""
                        INSERT INTO contest (id, code, name, description, rules_text, mechanic, start_at, end_at,
                          free_play_daily, max_plays_per_member_per_day, max_wins_per_member, distribution, seed, status,
                          created_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(c.id(), c.code(), c.name(), c.description(), c.rulesText(), c.mechanic(), ts(c.startAt()),
                        ts(c.endAt()), c.freePlayDaily(), c.maxPlaysPerMemberPerDay(), c.maxWinsPerMember(),
                        c.distribution(), c.seed(), c.status().name(), c.createdBy())
                .update();
    }

    public void update(Contest c) {
        jdbc.sql("""
                        UPDATE contest SET name = ?, description = ?, rules_text = ?, mechanic = ?, start_at = ?, end_at = ?,
                          free_play_daily = ?, max_plays_per_member_per_day = ?, max_wins_per_member = ?, distribution = ?,
                          seed = ?, instants_generated_at = ?, version = version + 1, updated_at = now()
                        WHERE id = ?
                        """)
                .params(c.name(), c.description(), c.rulesText(), c.mechanic(), ts(c.startAt()), ts(c.endAt()),
                        c.freePlayDaily(), c.maxPlaysPerMemberPerDay(), c.maxWinsPerMember(), c.distribution(), c.seed(),
                        ts(c.instantsGeneratedAt()), c.id())
                .update();
    }

    public void updateStatus(String id, ApprovalStatus status) {
        jdbc.sql("UPDATE contest SET status = ?, version = version + 1, updated_at = now() WHERE id = ?")
                .params(status.name(), id).update();
    }

    public void markGenerated(String id, long seed, Instant at) {
        jdbc.sql("UPDATE contest SET seed = ?, instants_generated_at = ?, updated_at = now() WHERE id = ?")
                .params(seed, ts(at), id).update();
    }

    // ---------- premi ----------

    public List<Prize> prizes(String contestId) {
        return jdbc.sql("SELECT " + PRIZE_COLUMNS + " FROM prize WHERE contest_id = ? ORDER BY sort_order, code")
                .param(contestId).query(ContestRepository::mapPrize).list();
    }

    public void insertPrize(Prize p) {
        jdbc.sql("""
                        INSERT INTO prize (id, contest_id, code, name, type, points, reward_code, quantity_total,
                          quantity_remaining, image_url, wheel_color, sort_order)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(p.id(), p.contestId(), p.code(), p.name(), p.type(), p.points(), p.rewardCode(), p.quantityTotal(),
                        p.quantityRemaining(), p.imageUrl(), p.wheelColor(), p.sortOrder())
                .update();
    }

    public void deletePrizes(String contestId) {
        jdbc.sql("DELETE FROM prize WHERE contest_id = ?").param(contestId).update();
    }

    /** Dopo una (ri)generazione ogni premio ha di nuovo tutte le sue unità. */
    public void resetRemaining(String contestId) {
        jdbc.sql("UPDATE prize SET quantity_remaining = quantity_total WHERE contest_id = ?").param(contestId).update();
    }

    /** Un'unità in meno del premio vinto (nella stessa transazione del claim). */
    public void decrementRemaining(String prizeId) {
        jdbc.sql("UPDATE prize SET quantity_remaining = quantity_remaining - 1 WHERE id = ? AND quantity_remaining > 0")
                .param(prizeId).update();
    }

    /** Residui = totale − istanti assegnati (seed dello storico). */
    public void recomputeRemaining(String contestId) {
        jdbc.sql("""
                        UPDATE prize p SET quantity_remaining = p.quantity_total - (SELECT count(*) FROM winning_instant w
                          WHERE w.prize_id = p.id AND w.status = 'CLAIMED')
                        WHERE p.contest_id = ?
                        """)
                .param(contestId).update();
    }

    public Optional<Prize> prize(String prizeId) {
        return jdbc.sql("SELECT " + PRIZE_COLUMNS + " FROM prize WHERE id = ?").param(prizeId)
                .query(ContestRepository::mapPrize).optional();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM contest").update();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    static Instant inst(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static Contest map(ResultSet rs, int n) throws SQLException {
        return new Contest(rs.getString("id"), rs.getString("code"), rs.getString("name"), rs.getString("description"),
                rs.getString("rules_text"), rs.getString("mechanic"), inst(rs, "start_at"), inst(rs, "end_at"),
                rs.getBoolean("free_play_daily"), intOrNull(rs, "max_plays_per_member_per_day"),
                intOrNull(rs, "max_wins_per_member"), rs.getString("distribution"), rs.getLong("seed"),
                inst(rs, "instants_generated_at"), ApprovalStatus.valueOf(rs.getString("status")), rs.getLong("version"),
                rs.getString("created_by"), inst(rs, "updated_at"));
    }

    private static Prize mapPrize(ResultSet rs, int n) throws SQLException {
        long points = rs.getLong("points");
        Long pts = rs.wasNull() ? null : points;
        return new Prize(rs.getString("id"), rs.getString("contest_id"), rs.getString("code"), rs.getString("name"),
                rs.getString("type"), pts, rs.getString("reward_code"), rs.getInt("quantity_total"),
                rs.getInt("quantity_remaining"), rs.getString("image_url"), rs.getString("wheel_color"),
                rs.getInt("sort_order"));
    }
}
