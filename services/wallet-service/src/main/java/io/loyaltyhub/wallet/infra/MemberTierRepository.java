package io.loyaltyhub.wallet.infra;

import io.loyaltyhub.wallet.domain.MemberTier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
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

    /** Salita immediata: nuovo livello, {@code previous_tier} e {@code since} aggiornati (docs §4.3). */
    public void upgrade(String memberId, String newTier, String previousTier) {
        jdbc.sql("""
                        UPDATE member_tier SET tier_code = ?, previous_tier = ?, since = now()
                        WHERE member_id = ?
                        """)
                .params(newTier, previousTier, memberId).update();
    }

    /** Conteggio dei membri per livello (BO-07, {@code GET /v1/tiers/distribution}). */
    public java.util.Map<String, Long> distribution() {
        java.util.Map<String, Long> counts = new java.util.HashMap<>();
        jdbc.sql("SELECT tier_code, count(*) AS n FROM member_tier GROUP BY tier_code")
                .query((rs, i) -> counts.put(rs.getString("tier_code"), rs.getLong("n"))).list();
        return counts;
    }

    public void updateStatus(String memberId, String status) {
        jdbc.sql("INSERT INTO member_tier (member_id, member_status) VALUES (?, ?) "
                        + "ON CONFLICT (member_id) DO UPDATE SET member_status = excluded.member_status")
                .params(memberId, status).update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM member_tier").update();
    }

    /**
     * Pagina di membri {@code ACTIVE} dopo {@code afterMemberId} (keyset, ordine per {@code member_id}). Con
     * {@code forUpdate} le righe restano bloccate fino al commit: la chiusura di edizione le legge così, e un
     * accredito STS concorrente ({@link #addPeriodSts}) aspetta invece di essere azzerato sotto i suoi piedi.
     */
    public List<MemberTier> findActiveMembersAfter(String afterMemberId, int limit, boolean forUpdate) {
        return jdbc.sql("SELECT member_id, tier_code, since, period_sts, previous_tier, member_status "
                        + "FROM member_tier WHERE member_status = 'ACTIVE' AND member_id > ? "
                        + "ORDER BY member_id LIMIT ?" + (forUpdate ? " FOR UPDATE" : ""))
                .params(afterMemberId == null ? "" : afterMemberId, limit)
                .query((rs, n) -> new MemberTier(
                        rs.getString("member_id"), rs.getString("tier_code"),
                        rs.getTimestamp("since") == null ? null : rs.getTimestamp("since").toInstant(),
                        rs.getLong("period_sts"), rs.getString("previous_tier"), rs.getString("member_status")))
                .list();
    }

    public void updateTierAndResetSts(String memberId, String newTier, String previousTier) {
        jdbc.sql("""
                        UPDATE member_tier SET tier_code = ?, previous_tier = ?, since = now(), period_sts = 0
                        WHERE member_id = ?
                        """)
                .params(newTier, previousTier, memberId).update();
    }

    public void resetPeriodSts(String memberId) {
        jdbc.sql("UPDATE member_tier SET period_sts = 0 WHERE member_id = ?")
                .param(memberId).update();
    }
}
