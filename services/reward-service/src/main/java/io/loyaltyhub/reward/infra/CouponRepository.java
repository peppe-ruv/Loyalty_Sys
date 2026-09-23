package io.loyaltyhub.reward.infra;

import io.loyaltyhub.reward.domain.Coupon;
import io.loyaltyhub.reward.domain.CouponPool;
import io.loyaltyhub.reward.domain.CouponStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Pool e codici coupon (docs/servizi/reward-service.md §2, §5). */
@Repository
public class CouponRepository {

    private static final String POOL_COLUMNS = "id, code, name, prefix, validity_days, seed, created_at";
    private static final String COUPON_COLUMNS = """
            code, pool_id, status, member_id, reward_code, origin, redemption_id, effect_id, issued_at, expires_at,
            used_at, voided_at""";

    private final JdbcClient jdbc;

    public CouponRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- pool ----------

    public List<CouponPool> pools() {
        return jdbc.sql("SELECT " + POOL_COLUMNS + " FROM coupon_pool ORDER BY code").query(CouponRepository::mapPool).list();
    }

    public Optional<CouponPool> pool(String idOrCode) {
        return jdbc.sql("SELECT " + POOL_COLUMNS + " FROM coupon_pool WHERE id = ? OR code = ?")
                .params(idOrCode, idOrCode).query(CouponRepository::mapPool).optional();
    }

    /** Riga del pool bloccata: serializza le generazioni concorrenti sullo stesso pool (stesso seme → stessi codici). */
    public Optional<CouponPool> lockPool(String id) {
        return jdbc.sql("SELECT " + POOL_COLUMNS + " FROM coupon_pool WHERE id = ? FOR UPDATE")
                .param(id).query(CouponRepository::mapPool).optional();
    }

    public void insertPool(CouponPool p) {
        jdbc.sql("INSERT INTO coupon_pool (id, code, name, prefix, validity_days, seed) VALUES (?, ?, ?, ?, ?, ?)")
                .params(p.id(), p.code(), p.name(), p.prefix(), p.validityDays(), p.seed()).update();
    }

    /** Conteggio dei codici per stato, per ogni pool (barra segmentata di BO-12). */
    public Map<String, Map<CouponStatus, Long>> countsByPool() {
        Map<String, Map<CouponStatus, Long>> out = new HashMap<>();
        jdbc.sql("SELECT pool_id, status, count(*) AS n FROM coupon GROUP BY pool_id, status")
                .query(rs -> {
                    out.computeIfAbsent(rs.getString("pool_id"), k -> new EnumMap<>(CouponStatus.class))
                            .put(CouponStatus.valueOf(rs.getString("status")), rs.getLong("n"));
                });
        return out;
    }

    public long countInPool(String poolId) {
        return jdbc.sql("SELECT count(*) FROM coupon WHERE pool_id = ?").param(poolId).query(Long.class).single();
    }

    public long countAvailable(String poolId) {
        return jdbc.sql("SELECT count(*) FROM coupon WHERE pool_id = ? AND status = 'AVAILABLE'")
                .param(poolId).query(Long.class).single();
    }

    // ---------- codici ----------

    /**
     * Inserisce codici nuovi {@code AVAILABLE} in un solo comando (fino a 5 000 codici: niente round-trip per codice);
     * quelli già esistenti, in qualunque pool, sono ignorati. Restituisce i codici effettivamente inseriti.
     */
    public List<String> insertAvailable(String poolId, Collection<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        INSERT INTO coupon (code, pool_id, status)
                        SELECT c, ?, 'AVAILABLE' FROM unnest(?::text[]) AS c
                        ON CONFLICT (code) DO NOTHING
                        RETURNING code
                        """)
                .params(poolId, TextArrays.literal(new ArrayList<>(codes)))
                .query(String.class).list();
    }

    public Optional<Coupon> find(String code) {
        return jdbc.sql("SELECT " + COUPON_COLUMNS + " FROM coupon WHERE code = ?").param(code)
                .query(CouponRepository::mapCoupon).optional();
    }

    public Optional<Coupon> lock(String code) {
        return jdbc.sql("SELECT " + COUPON_COLUMNS + " FROM coupon WHERE code = ? FOR UPDATE").param(code)
                .query(CouponRepository::mapCoupon).optional();
    }

    public Optional<Coupon> findByEffect(String effectId) {
        return jdbc.sql("SELECT " + COUPON_COLUMNS + " FROM coupon WHERE effect_id = ?").param(effectId)
                .query(CouponRepository::mapCoupon).optional();
    }

    public Optional<Coupon> findByRedemption(String redemptionId) {
        return jdbc.sql("SELECT " + COUPON_COLUMNS + " FROM coupon WHERE redemption_id = ?").param(redemptionId)
                .query(CouponRepository::mapCoupon).optional();
    }

    public long count(String poolId, String status, String memberId) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM coupon WHERE pool_id = ?");
        List<Object> params = filters(sql, poolId, status, memberId);
        return jdbc.sql(sql.toString()).params(params).query(Long.class).single();
    }

    public List<Coupon> search(String poolId, String status, String memberId, int page, int size) {
        StringBuilder sql = new StringBuilder("SELECT " + COUPON_COLUMNS + " FROM coupon WHERE pool_id = ?");
        List<Object> params = filters(sql, poolId, status, memberId);
        sql.append(" ORDER BY coalesce(issued_at, created_at) DESC, code LIMIT ? OFFSET ?");
        params.add(size);
        params.add(page * size);
        return jdbc.sql(sql.toString()).params(params).query(CouponRepository::mapCoupon).list();
    }

    public List<Coupon> byMember(String memberId) {
        return jdbc.sql("SELECT " + COUPON_COLUMNS + " FROM coupon WHERE member_id = ? ORDER BY issued_at DESC NULLS LAST, code")
                .param(memberId).query(CouponRepository::mapCoupon).list();
    }

    /**
     * Preleva e assegna il primo codice libero del pool ({@code FOR UPDATE SKIP LOCKED}, docs §5): richieste
     * concorrenti non si contendono lo stesso codice. Vuoto se il pool è esaurito.
     */
    public Optional<String> takeAvailable(String poolId) {
        return jdbc.sql("""
                        SELECT code FROM coupon WHERE pool_id = ? AND status = 'AVAILABLE'
                        ORDER BY created_at, code LIMIT 1 FOR UPDATE SKIP LOCKED
                        """)
                .param(poolId).query(String.class).optional();
    }

    public void markIssued(String code, String memberId, String rewardCode, String origin, String redemptionId,
                           String effectId, Instant issuedAt, Instant expiresAt) {
        jdbc.sql("""
                        UPDATE coupon SET status = 'ISSUED', member_id = ?, reward_code = ?, origin = ?, redemption_id = ?,
                          effect_id = ?, issued_at = ?, expires_at = ?
                        WHERE code = ?
                        """)
                .params(memberId, rewardCode, origin, redemptionId, effectId, ts(issuedAt), ts(expiresAt), code)
                .update();
    }

    public void markUsed(String code, Instant at) {
        jdbc.sql("UPDATE coupon SET status = 'USED', used_at = ? WHERE code = ?").params(ts(at), code).update();
    }

    public void markVoid(String code, Instant at) {
        jdbc.sql("UPDATE coupon SET status = 'VOID', voided_at = ? WHERE code = ?").params(ts(at), code).update();
    }

    public void markExpired(String code) {
        jdbc.sql("UPDATE coupon SET status = 'EXPIRED' WHERE code = ?").param(code).update();
    }

    /** Coupon {@code ISSUED} con scadenza passata a {@code asOf} → {@code EXPIRED}; restituisce quanti. */
    public int expireIssued(Instant asOf) {
        return jdbc.sql("UPDATE coupon SET status = 'EXPIRED' WHERE status = 'ISSUED' AND expires_at <= ?")
                .param(ts(asOf)).update();
    }

    /**
     * Solo per il seed: i primi {@code n} codici liberi del pool diventano {@code USED} (storico consumato prima della
     * demo, senza membro: le richieste d'esempio arrivano da {@code redemptions.json}).
     */
    public int seedConsume(String poolId, int n, Instant usedAt) {
        return jdbc.sql("""
                        UPDATE coupon SET status = 'USED', origin = 'REDEMPTION', used_at = ?
                        WHERE code IN (SELECT code FROM coupon WHERE pool_id = ? AND status = 'AVAILABLE'
                                       ORDER BY code LIMIT ?)
                        """)
                .params(ts(usedAt), poolId, n).update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM coupon").update();
        jdbc.sql("DELETE FROM coupon_pool").update();
    }

    private static List<Object> filters(StringBuilder sql, String poolId, String status, String memberId) {
        List<Object> params = new ArrayList<>();
        params.add(poolId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status.toUpperCase());
        }
        if (memberId != null && !memberId.isBlank()) {
            sql.append(" AND member_id = ?");
            params.add(memberId);
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

    private static CouponPool mapPool(ResultSet rs, int n) throws SQLException {
        return new CouponPool(rs.getString("id"), rs.getString("code"), rs.getString("name"), rs.getString("prefix"),
                rs.getInt("validity_days"), rs.getLong("seed"), inst(rs, "created_at"));
    }

    private static Coupon mapCoupon(ResultSet rs, int n) throws SQLException {
        return new Coupon(rs.getString("code"), rs.getString("pool_id"), CouponStatus.valueOf(rs.getString("status")),
                rs.getString("member_id"), rs.getString("reward_code"), rs.getString("origin"),
                rs.getString("redemption_id"), rs.getString("effect_id"), inst(rs, "issued_at"),
                inst(rs, "expires_at"), inst(rs, "used_at"), inst(rs, "voided_at"));
    }
}
