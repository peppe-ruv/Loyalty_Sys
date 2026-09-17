package io.loyaltyhub.catalogredemption.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Lotto di codici precaricati dal backoffice (RF-74): il riscatto ne preleva uno con SKIP LOCKED, mai lo stesso a due membri.
 * Quando il lotto è sotto la soglia di allarme il cruscotto lo segnala (RF-46).
 */
@Component
public class CouponPool {
    private final JdbcTemplate jdbc;
    private final io.loyaltyhub.common.metrics.LoyaltyMetrics metrics;
    public CouponPool(JdbcTemplate jdbc, io.loyaltyhub.common.metrics.LoyaltyMetrics metrics) { this.jdbc = jdbc; this.metrics = metrics; }

    public Optional<String> take(String poolId, UUID redemptionId) {
        List<String> codes = jdbc.queryForList("SELECT code FROM catalogredemption.coupon WHERE pool_id = ? AND redemption_id IS NULL ORDER BY code LIMIT 1 FOR UPDATE SKIP LOCKED", String.class, poolId);
        if (codes.isEmpty()) return Optional.empty();
        jdbc.update("UPDATE catalogredemption.coupon SET redemption_id = ?, assigned_at = now() WHERE pool_id = ? AND code = ?", redemptionId, poolId, codes.get(0));
        metrics.gauge("loyalty_coupon_pool_remaining", remaining(poolId), "pool", poolId); // RF-46/RF-120 scorta lotti
        return Optional.of(codes.get(0));
    }

    public int load(String poolId, List<String> codes) {
        int n = 0;
        for (String c : codes) if (!c.isBlank()) n += jdbc.update("INSERT INTO catalogredemption.coupon(pool_id, code) VALUES (?,?) ON CONFLICT DO NOTHING", poolId, c.trim());
        return n;
    }

    public long remaining(String poolId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM catalogredemption.coupon WHERE pool_id = ? AND redemption_id IS NULL", Long.class, poolId);
        return n == null ? 0 : n;
    }
}
