package io.loyaltyhub.reward.infra;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

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

    public void deleteAll() {
        jdbc.sql("DELETE FROM redemption").update();
    }
}
