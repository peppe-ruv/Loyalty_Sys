package io.loyaltyhub.member.infra;

import io.loyaltyhub.member.domain.MemberStats;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/** Statistiche di attività alimentate dalle azioni (docs/servizi/member-service.md §2, §5). */
@Repository
public class MemberStatsRepository {

    private final JdbcClient jdbc;

    public MemberStatsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<MemberStats> findByMemberId(String memberId) {
        return jdbc.sql("""
                        SELECT member_id, last_activity_at, actions_total, actions_by_type::text AS actions_by_type,
                               purchases_count, purchases_amount_90d, purchases_amount_total
                        FROM member_stats WHERE member_id = ?
                        """)
                .param(memberId)
                .query((rs, n) -> new MemberStats(
                        rs.getString("member_id"),
                        rs.getTimestamp("last_activity_at") == null ? null : rs.getTimestamp("last_activity_at").toInstant(),
                        rs.getLong("actions_total"),
                        rs.getString("actions_by_type"),
                        rs.getLong("purchases_count"),
                        rs.getBigDecimal("purchases_amount_90d"),
                        rs.getBigDecimal("purchases_amount_total")))
                .optional();
    }

    /**
     * Registra un'azione nelle statistiche (docs §5): incrementa il totale e il contatore per tipo;
     * {@code lastActivity} e gli importi acquisto sono nulli/zero per le azioni interne, che non
     * aggiornano l'ultima attività né i conteggi acquisti.
     */
    public void recordAction(String memberId, String shortType, Instant lastActivity,
                             long purchasesDelta, BigDecimal amountDelta) {
        java.sql.Timestamp ts = lastActivity == null ? null : java.sql.Timestamp.from(lastActivity);
        BigDecimal amount = amountDelta == null ? BigDecimal.ZERO : amountDelta;
        jdbc.sql("""
                        INSERT INTO member_stats
                          (member_id, last_activity_at, actions_total, actions_by_type,
                           purchases_count, purchases_amount_90d, purchases_amount_total)
                        VALUES (?, ?, 1, jsonb_build_object(?, 1), ?, ?, ?)
                        ON CONFLICT (member_id) DO UPDATE SET
                          last_activity_at = GREATEST(member_stats.last_activity_at, excluded.last_activity_at),
                          actions_total = member_stats.actions_total + 1,
                          actions_by_type = jsonb_set(
                              member_stats.actions_by_type, ARRAY[?::text],
                              to_jsonb(coalesce((member_stats.actions_by_type ->> ?)::bigint, 0) + 1)),
                          purchases_count = member_stats.purchases_count + excluded.purchases_count,
                          purchases_amount_90d = member_stats.purchases_amount_90d + excluded.purchases_amount_90d,
                          purchases_amount_total = member_stats.purchases_amount_total + excluded.purchases_amount_total
                        """)
                .params(memberId, ts, shortType, purchasesDelta, amount, amount, shortType, shortType)
                .update();
    }

    /** Reset demo: azzera le statistiche del membro (riga vuota, coerente con storia assente). */
    public void resetTo(String memberId) {
        jdbc.sql("""
                        INSERT INTO member_stats (member_id) VALUES (?)
                        ON CONFLICT (member_id) DO UPDATE SET
                          last_activity_at = NULL, actions_total = 0, actions_by_type = '{}'::jsonb,
                          purchases_count = 0, purchases_amount_90d = 0, purchases_amount_total = 0
                        """)
                .param(memberId).update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM member_stats").update();
    }
}
