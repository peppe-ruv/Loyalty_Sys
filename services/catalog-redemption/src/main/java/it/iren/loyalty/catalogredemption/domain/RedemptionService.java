package it.iren.loyalty.catalogredemption.domain;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * Riscatto atomico (RF-15): verifica fascia, riserva stock con lock, scala i punti sul ledger, registra il riscatto.
 * Se il ledger rifiuta (saldo insufficiente) la transazione locale fa rollback e lo stock torna disponibile.
 * La consegna (buono digitale, fornitore) è una saga separata avviata dall'evento di riscatto (D10).
 */
@Service
public class RedemptionService {
    public record Result(UUID redemptionId, String status, String code) {}
    public static class RedemptionRejected extends RuntimeException { public RedemptionRejected(String m) { super(m); } }

    private final JdbcTemplate jdbc;
    private final RestClient ledger;

    public RedemptionService(JdbcTemplate jdbc, RestClient.Builder builder) {
        this.jdbc = jdbc;
        this.ledger = builder.baseUrl(System.getenv().getOrDefault("LEDGER_URL", "http://ledger:8083")).build();
    }

    @Transactional
    @CircuitBreaker(name = "ledger")
    public Result redeem(String memberId, String memberTierOrder, UUID rewardId) {
        var reward = jdbc.queryForMap("SELECT points_cost, min_tier_order, stock, type FROM catalogredemption.reward WHERE id = ? AND status = 'ACTIVE' FOR UPDATE", rewardId);
        int minTier = ((Number) reward.get("min_tier_order")).intValue();
        if (Integer.parseInt(memberTierOrder) < minTier) throw new RedemptionRejected("TIER_TOO_LOW");
        long stock = ((Number) reward.get("stock")).longValue();
        if (stock <= 0) throw new RedemptionRejected("OUT_OF_STOCK");
        long cost = ((Number) reward.get("points_cost")).longValue();

        UUID id = UUID.randomUUID();
        String actionKey = "redemption:" + id + ":DEBIT";
        try {
            ledger.post().uri("/v1/ledger/debits").body(Map.of("memberId", memberId, "actionKey", actionKey, "points", cost, "reason", "REDEMPTION")).retrieve().toBodilessEntity();
        } catch (org.springframework.web.client.HttpClientErrorException.Conflict e) {
            throw new RedemptionRejected("INSUFFICIENT_BALANCE");
        }
        jdbc.update("UPDATE catalogredemption.reward SET stock = stock - 1 WHERE id = ?", rewardId);
        jdbc.update("INSERT INTO catalogredemption.redemption(id, member_id, reward_id, points, status, requested_at) VALUES (?,?,?,?,'CONFIRMED', now())", id, memberId, rewardId, cost);
        return new Result(id, "CONFIRMED", null);
    }
}
