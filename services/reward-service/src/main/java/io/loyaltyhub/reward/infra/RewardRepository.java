package io.loyaltyhub.reward.infra;

import io.loyaltyhub.reward.domain.Reward;
import io.loyaltyhub.reward.domain.RewardStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Premi e stock (docs/servizi/reward-service.md §2, §5). */
@Repository
public class RewardRepository {

    private static final String COLUMNS = """
            id, code, name, description, terms, image_url, type, category_code, band_code, fulfilment, coupon_pool_id,
            stock_total, stock_remaining, per_member_limit, eligible_tiers, eligible_segments, valid_from, valid_to,
            status, version, created_by, updated_at""";

    private final JdbcClient jdbc;

    public RewardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Reward> search(String status, String band, String category, String type, String q) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM reward WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) { sql.append(" AND status = ?"); params.add(status.toUpperCase()); }
        if (band != null && !band.isBlank()) { sql.append(" AND band_code = ?"); params.add(band); }
        if (category != null && !category.isBlank()) { sql.append(" AND category_code = ?"); params.add(category); }
        if (type != null && !type.isBlank()) { sql.append(" AND type = ?"); params.add(type.toUpperCase()); }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (code ILIKE ? OR name ILIKE ?)");
            params.add("%" + q + "%");
            params.add("%" + q + "%");
        }
        sql.append(" ORDER BY band_code, code");
        return jdbc.sql(sql.toString()).params(params).query(RewardRepository::map).list();
    }

    public List<Reward> findAll() {
        return search(null, null, null, null, null);
    }

    public Optional<Reward> findById(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM reward WHERE id = ?").param(id).query(RewardRepository::map).optional();
    }

    public Optional<Reward> findByCode(String code) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM reward WHERE code = ?").param(code).query(RewardRepository::map).optional();
    }

    /** Premi che attingono al pool (colonna «premio collegato» di BO-12). */
    public List<Reward> findByPool(String poolId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM reward WHERE coupon_pool_id = ? ORDER BY code")
                .param(poolId).query(RewardRepository::map).list();
    }

    public void insert(Reward r) {
        jdbc.sql("""
                        INSERT INTO reward (id, code, name, description, terms, image_url, type, category_code, band_code,
                          fulfilment, coupon_pool_id, stock_total, stock_remaining, per_member_limit, eligible_tiers,
                          eligible_segments, valid_from, valid_to, status, version, created_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::text[], ?::text[], ?, ?, ?, 0, ?)
                        """)
                .params(r.id(), r.code(), r.name(), r.description(), r.terms(), r.imageUrl(), r.type(), r.categoryCode(),
                        r.bandCode(), r.fulfilment(), r.couponPoolId(), r.stockTotal(), r.stockRemaining(),
                        r.perMemberLimit(), TextArrays.literal(r.eligibleTiers()), TextArrays.literal(r.eligibleSegments()),
                        ts(r.validFrom()), ts(r.validTo()), r.status().name(), r.createdBy())
                .update();
    }

    /**
     * Richieste che tengono un'unità di stock (Q-280 DECISA): {@code PENDING}, {@code CONFIRMED}, {@code FULFILLED} del
     * premio della riga {@code reward} corrente. {@code REJECTED} e {@code CANCELLED} l'hanno restituita.
     */
    private static final String HELD = """
            (SELECT count(*) FROM redemption d
              WHERE d.reward_code = reward.code AND d.status IN ('PENDING', 'CONFIRMED', 'FULFILLED'))""";

    /**
     * Riscrive i campi modificabili se la versione è ancora {@code expectedVersion}; {@code false} = modificato nel
     * frattempo (409, M7.6). Lo stock residuo si ricalcola sulla riga corrente (residuo + delta del totale), così una
     * richiesta premio arrivata tra lettura e scrittura non si perde; Q-280 DECISA (niente vendite oltre lo stock): il
     * residuo non supera mai {@code totale − richieste che tengono stock} (mai sotto zero), anche passando da
     * illimitato a limitato.
     */
    public boolean update(Reward r, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE reward SET name = ?, description = ?, terms = ?, image_url = ?, type = ?, category_code = ?,
                          band_code = ?, fulfilment = ?, coupon_pool_id = ?,
                          stock_remaining = CASE WHEN cast(? AS integer) IS NULL THEN NULL
                                                 WHEN stock_total IS NULL THEN greatest(0, cast(? AS integer) - {HELD})
                                                 ELSE greatest(0, least(stock_remaining + (cast(? AS integer) - stock_total),
                                                                        cast(? AS integer) - {HELD})) END,
                          stock_total = ?, per_member_limit = ?, eligible_tiers = ?::text[], eligible_segments = ?::text[],
                          valid_from = ?, valid_to = ?, version = version + 1, updated_at = now()
                        WHERE id = ? AND version = ?
                        """.replace("{HELD}", HELD))
                .params(r.name(), r.description(), r.terms(), r.imageUrl(), r.type(), r.categoryCode(), r.bandCode(),
                        r.fulfilment(), r.couponPoolId(), r.stockTotal(), r.stockTotal(), r.stockTotal(), r.stockTotal(),
                        r.stockTotal(), r.perMemberLimit(), TextArrays.literal(r.eligibleTiers()), TextArrays.literal(r.eligibleSegments()),
                        ts(r.validFrom()), ts(r.validTo()), r.id(), expectedVersion)
                .update() == 1;
    }

    public void updateStatus(String id, RewardStatus status) {
        jdbc.sql("UPDATE reward SET status = ?, version = version + 1, updated_at = now() WHERE id = ?")
                .params(status.name(), id).update();
    }

    /** Prenota un'unità di stock in modo atomico; {@code false} se esaurito (docs §5). Illimitato → sempre {@code true}. */
    public boolean reserveStock(String code) {
        int updated = jdbc.sql("""
                        UPDATE reward SET stock_remaining = stock_remaining - 1, updated_at = now()
                        WHERE code = ? AND (stock_total IS NULL OR stock_remaining > 0)
                        """)
                .param(code).update();
        return updated == 1;
    }

    /**
     * Restituisce un'unità di stock (richiesta respinta, scaduta o annullata), senza superare il totale né, Q-280
     * DECISA, {@code totale − richieste che ancora tengono stock} (la richiesta chiusa va già marcata nella stessa
     * transazione): dopo una riduzione del totale sotto le prenotazioni il ripristino non riapre stock venduto.
     */
    public void releaseStock(String code) {
        jdbc.sql("""
                        UPDATE reward SET stock_remaining = greatest(0, least(stock_remaining + 1, stock_total,
                                                                              stock_total - {HELD})),
                          updated_at = now()
                        WHERE code = ? AND stock_total IS NOT NULL
                        """.replace("{HELD}", HELD))
                .param(code).update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM reward").update();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant inst(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static Reward map(ResultSet rs, int n) throws SQLException {
        return new Reward(rs.getString("id"), rs.getString("code"), rs.getString("name"), rs.getString("description"),
                rs.getString("terms"), rs.getString("image_url"), rs.getString("type"), rs.getString("category_code"),
                rs.getString("band_code"), rs.getString("fulfilment"), rs.getString("coupon_pool_id"),
                intOrNull(rs, "stock_total"), intOrNull(rs, "stock_remaining"), intOrNull(rs, "per_member_limit"),
                TextArrays.toList(rs.getArray("eligible_tiers")), TextArrays.toList(rs.getArray("eligible_segments")),
                inst(rs, "valid_from"), inst(rs, "valid_to"), RewardStatus.valueOf(rs.getString("status")),
                rs.getLong("version"), rs.getString("created_by"), inst(rs, "updated_at"));
    }
}
