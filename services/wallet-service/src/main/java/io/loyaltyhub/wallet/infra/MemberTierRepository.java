package io.loyaltyhub.wallet.infra;

import io.loyaltyhub.wallet.domain.MemberTier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Livello corrente dei membri (docs/servizi/wallet-service.md §2). */
@Repository
public class MemberTierRepository {

    private final JdbcClient jdbc;

    public MemberTierRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<MemberTier> find(String memberId) {
        return jdbc.sql("SELECT member_id, tier_code, since, period_sts, previous_tier, member_status "
                        + "FROM member_tier WHERE member_id = ?")
                .param(memberId)
                .query((rs, n) -> new MemberTier(
                        rs.getString("member_id"), rs.getString("tier_code"),
                        rs.getTimestamp("since") == null ? null : rs.getTimestamp("since").toInstant(),
                        rs.getLong("period_sts"), rs.getString("previous_tier"), rs.getString("member_status")))
                .optional();
    }

    /** Crea il livello BASE se assente (idempotente). */
    public void ensureBase(String memberId) {
        jdbc.sql("INSERT INTO member_tier (member_id, tier_code) VALUES (?, 'BASE') "
                + "ON CONFLICT (member_id) DO NOTHING").param(memberId).update();
    }

    public void set(String memberId, String tierCode, long periodSts) {
        jdbc.sql("""
                        INSERT INTO member_tier (member_id, tier_code, period_sts) VALUES (?, ?, ?)
                        ON CONFLICT (member_id) DO UPDATE SET tier_code = excluded.tier_code,
                          period_sts = excluded.period_sts
                        """)
                .params(memberId, tierCode, periodSts).update();
    }

    public void addPeriodSts(String memberId, long delta) {
        jdbc.sql("UPDATE member_tier SET period_sts = period_sts + ? WHERE member_id = ?")
                .params(delta, memberId).update();
    }

    public void updateStatus(String memberId, String status) {
        jdbc.sql("INSERT INTO member_tier (member_id, member_status) VALUES (?, ?) "
                        + "ON CONFLICT (member_id) DO UPDATE SET member_status = excluded.member_status")
                .params(memberId, status).update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM member_tier").update();
    }
}
