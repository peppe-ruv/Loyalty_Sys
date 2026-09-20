package io.loyaltyhub.campaign.infra;

import io.loyaltyhub.campaign.engine.Counters;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * Contatori dei limiti, budget e storico azioni (docs/servizi/campaign-service.md §2, §5). Implementa
 * {@link Counters} in lettura per il motore; gli incrementi (transazionali) avvengono dopo la valutazione.
 */
@Repository
public class CounterRepository implements Counters {

    private final JdbcClient jdbc;

    public CounterRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- letture (Counters) ----------

    @Override
    public int memberMatches(String campaignId, String memberId, String period, String periodKey) {
        Integer n = jdbc.sql("""
                        SELECT matches FROM campaign_counter
                        WHERE campaign_id = ? AND member_id = ? AND period = ? AND period_key = ?
                        """)
                .params(campaignId, memberId, period, periodKey).query(Integer.class).optional().orElse(0);
        return n;
    }

    @Override
    public long globalPointsDecided(String campaignId) {
        return jdbc.sql("SELECT coalesce(points_decided, 0) FROM campaign_totals WHERE campaign_id = ?")
                .param(campaignId).query(Long.class).optional().orElse(0L);
    }

    @Override
    public long globalMatches(String campaignId) {
        return jdbc.sql("SELECT coalesce(matches, 0) FROM campaign_totals WHERE campaign_id = ?")
                .param(campaignId).query(Long.class).optional().orElse(0L);
    }

    @Override
    public long historyActionCount(String memberId, String actionType) {
        return jdbc.sql("SELECT coalesce(count, 0) FROM member_action_counter WHERE member_id = ? AND action_type = ?")
                .params(memberId, actionType).query(Long.class).optional().orElse(0L);
    }

    @Override
    public long historyDaysSinceLastAction(String memberId, String actionType) {
        Instant last = jdbc.sql("SELECT last_at FROM member_action_counter WHERE member_id = ? AND action_type = ?")
                .params(memberId, actionType)
                .query((rs, n) -> rs.getTimestamp("last_at") == null ? null : rs.getTimestamp("last_at").toInstant())
                .optional().orElse(null);
        return last == null ? -1 : java.time.Duration.between(last, Instant.now()).toDays();
    }

    // ---------- incrementi (transazionali) ----------

    /** Incremento del contatore per (campagna, membro, periodo): matches +1, points += delta. */
    public void addMemberMatch(String campaignId, String memberId, String period, String periodKey, long pointsDelta) {
        jdbc.sql("""
                        INSERT INTO campaign_counter (campaign_id, member_id, period, period_key, matches, points)
                        VALUES (?, ?, ?, ?, 1, ?)
                        ON CONFLICT (campaign_id, member_id, period, period_key) DO UPDATE SET
                          matches = campaign_counter.matches + 1,
                          points = campaign_counter.points + excluded.points
                        """)
                .params(campaignId, memberId, period, periodKey, pointsDelta).update();
    }

    /** Totali della campagna: match +1, punti decisi += delta, ultimo match. */
    public void addTotals(String campaignId, long pointsDelta, Instant at) {
        jdbc.sql("""
                        INSERT INTO campaign_totals (campaign_id, matches, points_decided, last_match_at)
                        VALUES (?, 1, ?, ?)
                        ON CONFLICT (campaign_id) DO UPDATE SET
                          matches = campaign_totals.matches + 1,
                          points_decided = campaign_totals.points_decided + excluded.points_decided,
                          last_match_at = excluded.last_match_at
                        """)
                .params(campaignId, pointsDelta, java.sql.Timestamp.from(at)).update();
    }

    /** Punti effettivamente accreditati (dal fatto {@code wallet.points.earned}). */
    public void addPointsGranted(String campaignId, long amount) {
        jdbc.sql("""
                        INSERT INTO campaign_totals (campaign_id, points_granted)
                        VALUES (?, ?)
                        ON CONFLICT (campaign_id) DO UPDATE SET
                          points_granted = campaign_totals.points_granted + excluded.points_granted
                        """)
                .params(campaignId, amount).update();
    }

    /** Storico azioni per membro/tipo (spazio {@code history.*}). */
    public void recordAction(String memberId, String actionType, Instant at) {
        java.sql.Timestamp ts = java.sql.Timestamp.from(at);
        jdbc.sql("""
                        INSERT INTO member_action_counter (member_id, action_type, count, first_at, last_at)
                        VALUES (?, ?, 1, ?, ?)
                        ON CONFLICT (member_id, action_type) DO UPDATE SET
                          count = member_action_counter.count + 1,
                          last_at = GREATEST(member_action_counter.last_at, excluded.last_at)
                        """)
                .params(memberId, actionType, ts, ts).update();
    }

    public Totals totals(String campaignId) {
        return jdbc.sql("""
                        SELECT matches, unique_members, points_decided, points_granted
                        FROM campaign_totals WHERE campaign_id = ?
                        """)
                .param(campaignId)
                .query((rs, n) -> new Totals(rs.getLong("matches"), rs.getLong("unique_members"),
                        rs.getLong("points_decided"), rs.getLong("points_granted")))
                .optional().orElse(new Totals(0, 0, 0, 0));
    }

    public record Totals(long matches, long uniqueMembers, long pointsDecided, long pointsGranted) {
    }

    /** Membri unici che hanno attivato la campagna (da {@code campaign_counter}). */
    public long uniqueMembers(String campaignId) {
        return jdbc.sql("SELECT count(DISTINCT member_id) FROM campaign_counter WHERE campaign_id = ?")
                .param(campaignId).query(Long.class).single();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM campaign_counter").update();
        jdbc.sql("DELETE FROM campaign_totals").update();
        jdbc.sql("DELETE FROM member_action_counter").update();
    }
}
