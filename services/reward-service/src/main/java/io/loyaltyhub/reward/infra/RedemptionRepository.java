package io.loyaltyhub.reward.infra;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Richieste premio (docs/servizi/reward-service.md §2). */
@Repository
public class RedemptionRepository {

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
}
