package io.loyaltyhub.reward.infra;

import io.loyaltyhub.reward.domain.Redemption;
import io.loyaltyhub.reward.domain.RedemptionStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Richieste premio e loro cronologia (docs/servizi/reward-service.md §2). */
@Repository
public class RedemptionRepository {

    private static final String COLUMNS = """
            id, member_id, reward_code, reward_name, points_cost, status, reject_reason, needs_attention, coupon_code,
            fulfilment_note, shipping::text AS shipping, correlation_id, requested_at, confirmed_at, closed_at, actor""";

    private final JdbcClient jdbc;

    public RedemptionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Richieste del membro per il premio che contano per il limite per membro (non respinte né annullate). */
    public long countActive(String memberId, String rewardCode) {
        return jdbc.sql("""
                        SELECT count(*) FROM redemption
                        WHERE member_id = ? AND reward_code = ? AND status IN ('PENDING', 'CONFIRMED', 'FULFILLED')
                        """)
                .params(memberId, rewardCode).query(Long.class).single();
    }

    public void insert(Redemption r) {
        jdbc.sql("""
                        INSERT INTO redemption (id, member_id, reward_code, reward_name, points_cost, status, shipping,
                          correlation_id, requested_at, actor)
                        VALUES (?, ?, ?, ?, ?, ?, cast(? AS jsonb), ?, ?, ?)
                        """)
                .params(r.id(), r.memberId(), r.rewardCode(), r.rewardName(), r.pointsCost(), r.status().name(),
                        r.shippingJson(), r.correlationId(), ts(r.requestedAt()), r.actor())
                .update();
    }

    public Optional<Redemption> find(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM redemption WHERE id = ?").param(id)
                .query(RedemptionRepository::map).optional();
    }

    /** Riga bloccata: gli esiti della saga (spesa, rifiuto, timeout, annullo) si serializzano sulla richiesta. */
    public Optional<Redemption> lock(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM redemption WHERE id = ? FOR UPDATE").param(id)
                .query(RedemptionRepository::map).optional();
    }

    public void confirm(String id, Instant at) {
        jdbc.sql("UPDATE redemption SET status = 'CONFIRMED', confirmed_at = ? WHERE id = ?").params(ts(at), id).update();
    }

    public void fulfil(String id, String couponCode, String note, Instant at) {
        jdbc.sql("""
                        UPDATE redemption SET status = 'FULFILLED', coupon_code = coalesce(?, coupon_code),
                          fulfilment_note = coalesce(?, fulfilment_note), needs_attention = false, closed_at = ?
                        WHERE id = ?
                        """)
                .params(couponCode, note, ts(at), id).update();
    }

    public void close(String id, RedemptionStatus status, String reason, Instant at) {
        jdbc.sql("UPDATE redemption SET status = ?, reject_reason = ?, closed_at = ? WHERE id = ?")
                .params(status.name(), reason, ts(at), id).update();
    }

    public void flagAttention(String id, boolean value) {
        jdbc.sql("UPDATE redemption SET needs_attention = ? WHERE id = ?").params(value, id).update();
    }

    public void addHistory(String historyId, String redemptionId, RedemptionStatus status, String note, String actor, Instant at) {
        jdbc.sql("INSERT INTO redemption_history (id, redemption_id, status, note, actor, at) VALUES (?, ?, ?, ?, ?, ?)")
                .params(historyId, redemptionId, status.name(), note, actor, ts(at)).update();
    }

    public record HistoryItem(String status, String note, String actor, Instant at) {
    }

    public List<HistoryItem> history(String redemptionId) {
        return jdbc.sql("SELECT status, note, actor, at FROM redemption_history WHERE redemption_id = ? ORDER BY at, id")
                .param(redemptionId)
                .query((rs, n) -> new HistoryItem(rs.getString("status"), rs.getString("note"), rs.getString("actor"),
                        inst(rs, "at")))
                .list();
    }

    public List<Redemption> byMember(String memberId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM redemption WHERE member_id = ? ORDER BY requested_at DESC")
                .param(memberId).query(RedemptionRepository::map).list();
    }

    public List<Redemption> search(String status, String memberId, String rewardCode, Boolean needsAttention,
                                   Instant from, Instant to, int page, int size) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM redemption WHERE 1=1");
        List<Object> params = filters(sql, status, memberId, rewardCode, needsAttention, from, to);
        sql.append(" ORDER BY requested_at DESC, id LIMIT ? OFFSET ?");
        params.add(size);
        params.add(page * size);
        return jdbc.sql(sql.toString()).params(params).query(RedemptionRepository::map).list();
    }

    public long count(String status, String memberId, String rewardCode, Boolean needsAttention, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM redemption WHERE 1=1");
        List<Object> params = filters(sql, status, memberId, rewardCode, needsAttention, from, to);
        return jdbc.sql(sql.toString()).params(params).query(Long.class).single();
    }

    /** Richieste ancora {@code PENDING} chieste prima di {@code before} (timeout della saga). */
    public List<String> pendingBefore(Instant before) {
        return jdbc.sql("SELECT id FROM redemption WHERE status = 'PENDING' AND requested_at < ? ORDER BY requested_at")
                .param(ts(before)).query(String.class).list();
    }

    /** Numero di richieste per stato (BO-10 statistiche, BO-13 schede). */
    public Map<String, Long> countByStatus() {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.sql("SELECT status, count(*) AS n FROM redemption GROUP BY status ORDER BY status")
                .query(rs -> {
                    out.put(rs.getString("status"), rs.getLong("n"));
                });
        return out;
    }

    public record TopReward(String rewardCode, String rewardName, long redemptions) {
    }

    /** Premi più richiesti (richieste non respinte né annullate). */
    public List<TopReward> topRewards(int limit) {
        return jdbc.sql("""
                        SELECT reward_code, max(reward_name) AS reward_name, count(*) AS n FROM redemption
                        WHERE status IN ('PENDING', 'CONFIRMED', 'FULFILLED')
                        GROUP BY reward_code ORDER BY n DESC, reward_code LIMIT ?
                        """)
                .param(limit)
                .query((rs, i) -> new TopReward(rs.getString("reward_code"), rs.getString("reward_name"), rs.getLong("n")))
                .list();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM redemption").update();
    }

    private static List<Object> filters(StringBuilder sql, String status, String memberId, String rewardCode,
                                        Boolean needsAttention, Instant from, Instant to) {
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status.toUpperCase());
        }
        if (memberId != null && !memberId.isBlank()) {
            sql.append(" AND member_id = ?");
            params.add(memberId);
        }
        if (rewardCode != null && !rewardCode.isBlank()) {
            sql.append(" AND reward_code = ?");
            params.add(rewardCode);
        }
        if (needsAttention != null) {
            sql.append(" AND needs_attention = ?");
            params.add(needsAttention);
        }
        if (from != null) {
            sql.append(" AND requested_at >= ?");
            params.add(ts(from));
        }
        if (to != null) {
            sql.append(" AND requested_at < ?");
            params.add(ts(to));
        }
        return params;
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant inst(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static Redemption map(ResultSet rs, int n) throws SQLException {
        return new Redemption(rs.getString("id"), rs.getString("member_id"), rs.getString("reward_code"),
                rs.getString("reward_name"), rs.getLong("points_cost"), RedemptionStatus.valueOf(rs.getString("status")),
                rs.getString("reject_reason"), rs.getBoolean("needs_attention"), rs.getString("coupon_code"),
                rs.getString("fulfilment_note"), rs.getString("shipping"), rs.getString("correlation_id"),
                inst(rs, "requested_at"), inst(rs, "confirmed_at"), inst(rs, "closed_at"), rs.getString("actor"));
    }
}
