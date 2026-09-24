package io.loyaltyhub.member.infra;

import io.loyaltyhub.member.domain.MemberStats;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Statistiche di attività alimentate dalle azioni (docs/servizi/member-service.md §2, §5), più i contatori giornalieri
 * {@code member_activity_day} da cui derivano le finestre mobili dei criteri di segmento (docs/03 §10).
 */
@Repository
public class MemberStatsRepository {

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");

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
     * Registra un'azione nelle statistiche (docs §2, §5): contatore giornaliero (per le finestre mobili dei segmenti),
     * totale, {@code actions_by_type} nella forma {@code {type: {count30d, total, lastAt}}} e spesa. {@code lastActivity}
     * e gli importi acquisto sono nulli/zero per le azioni interne, che non aggiornano l'ultima attività né gli acquisti.
     * Le finestre ({@code count30d}, {@code purchases_amount_90d}) sono calcolate rispetto a {@code today} (Europe/Rome).
     */
    public void recordAction(String memberId, String shortType, Instant actionTime, Instant lastActivity,
                             long purchasesDelta, BigDecimal amountDelta, LocalDate today) {
        java.sql.Timestamp last = lastActivity == null ? null : java.sql.Timestamp.from(lastActivity);
        java.sql.Timestamp at = java.sql.Timestamp.from(actionTime);
        BigDecimal amount = amountDelta == null ? BigDecimal.ZERO : amountDelta;
        LocalDate day = LocalDate.ofInstant(actionTime, ROME);
        jdbc.sql("""
                        INSERT INTO member_activity_day (member_id, day, action_type, count, purchase_amount)
                        VALUES (?, ?, ?, 1, ?)
                        ON CONFLICT (member_id, day, action_type) DO UPDATE SET
                          count = member_activity_day.count + 1,
                          purchase_amount = member_activity_day.purchase_amount + excluded.purchase_amount
                        """)
                .params(memberId, day, shortType, amount)
                .update();
        jdbc.sql("INSERT INTO member_stats (member_id) VALUES (?) ON CONFLICT (member_id) DO NOTHING").param(memberId).update();
        jdbc.sql("""
                        UPDATE member_stats SET
                          last_activity_at = GREATEST(last_activity_at, ?::timestamptz),
                          actions_total = actions_total + 1,
                          purchases_count = purchases_count + ?,
                          purchases_amount_total = purchases_amount_total + ?,
                          purchases_amount_90d = (SELECT coalesce(sum(d.purchase_amount), 0) FROM member_activity_day d
                                                  WHERE d.member_id = ? AND d.day > ? AND d.day <= ?),
                          actions_by_type = jsonb_set(actions_by_type, ARRAY[?::text], jsonb_build_object(
                              'total', coalesce((actions_by_type -> ? ->> 'total')::bigint, 0) + 1,
                              'lastAt', to_jsonb(GREATEST((actions_by_type -> ? ->> 'lastAt')::timestamptz, ?::timestamptz)),
                              'count30d', (SELECT coalesce(sum(d.count), 0) FROM member_activity_day d
                                           WHERE d.member_id = ? AND d.action_type = ? AND d.day > ? AND d.day <= ?)))
                        WHERE member_id = ?
                        """)
                .params(last, purchasesDelta, amount, memberId, today.minusDays(90), today,
                        shortType, shortType, shortType, at, memberId, shortType, today.minusDays(30), today, memberId)
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
        jdbc.sql("DELETE FROM member_activity_day").update();
        jdbc.sql("DELETE FROM member_stats").update();
    }
}
