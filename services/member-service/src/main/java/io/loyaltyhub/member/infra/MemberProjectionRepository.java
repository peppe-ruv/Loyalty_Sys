package io.loyaltyhub.member.infra;

import io.loyaltyhub.member.domain.MemberProjection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Proiezione di saldi/tier (docs/servizi/member-service.md §2). Riflette gli eventi del wallet. */
@Repository
public class MemberProjectionRepository {

    private final JdbcClient jdbc;

    public MemberProjectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<MemberProjection> findByMemberId(String memberId) {
        return jdbc.sql("""
                        SELECT member_id, tier_code, period_sts, balance_pts, pending_pts,
                               lifetime_earned_pts, updated_at
                        FROM member_projection WHERE member_id = ?
                        """)
                .param(memberId)
                .query((rs, n) -> new MemberProjection(
                        rs.getString("member_id"), rs.getString("tier_code"),
                        rs.getLong("period_sts"), rs.getLong("balance_pts"), rs.getLong("pending_pts"),
                        rs.getLong("lifetime_earned_pts"),
                        rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant()))
                .optional();
    }

    /** Crea/aggiorna l'intera proiezione (usato dal seeder). */
    public void upsert(String memberId, String tierCode, long periodSts, long balancePts,
                       long pendingPts, long lifetimeEarnedPts) {
        jdbc.sql("""
                        INSERT INTO member_projection
                          (member_id, tier_code, period_sts, balance_pts, pending_pts, lifetime_earned_pts, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, now())
                        ON CONFLICT (member_id) DO UPDATE SET
                          tier_code = excluded.tier_code, period_sts = excluded.period_sts,
                          balance_pts = excluded.balance_pts, pending_pts = excluded.pending_pts,
                          lifetime_earned_pts = excluded.lifetime_earned_pts, updated_at = now()
                        """)
                .params(memberId, tierCode, periodSts, balancePts, pendingPts, lifetimeEarnedPts)
                .update();
    }

    /** Riflette un accredito punti dal wallet (docs §5): {@code balance_pts = balanceAfter}. */
    public void applyPointsBalance(String memberId, long balanceAfter, long lifetimeEarnedDelta) {
        jdbc.sql("""
                        INSERT INTO member_projection (member_id, balance_pts, lifetime_earned_pts, updated_at)
                        VALUES (?, ?, GREATEST(?, 0), now())
                        ON CONFLICT (member_id) DO UPDATE SET
                          balance_pts = excluded.balance_pts,
                          lifetime_earned_pts = member_projection.lifetime_earned_pts + GREATEST(?, 0),
                          updated_at = now()
                        """)
                .params(memberId, balanceAfter, lifetimeEarnedDelta, lifetimeEarnedDelta)
                .update();
    }

    /** Riflette il saldo STS dell'edizione (fatti {@code wallet.points.*} in valuta STS): {@code period_sts = balanceAfter}. */
    public void applyStatusBalance(String memberId, long balanceAfter) {
        jdbc.sql("""
                        INSERT INTO member_projection (member_id, period_sts, updated_at)
                        VALUES (?, ?, now())
                        ON CONFLICT (member_id) DO UPDATE SET period_sts = excluded.period_sts, updated_at = now()
                        """)
                .params(memberId, balanceAfter)
                .update();
    }

    /** Riflette un passaggio di livello dal wallet/motore (docs §5): {@code tier_code = newTier}. */
    public void applyTier(String memberId, String tierCode) {
        jdbc.sql("""
                        INSERT INTO member_projection (member_id, tier_code, updated_at)
                        VALUES (?, ?, now())
                        ON CONFLICT (member_id) DO UPDATE SET tier_code = excluded.tier_code, updated_at = now()
                        """)
                .params(memberId, tierCode)
                .update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM member_projection").update();
    }
}
