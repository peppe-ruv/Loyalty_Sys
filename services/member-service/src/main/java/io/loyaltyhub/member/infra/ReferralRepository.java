package io.loyaltyhub.member.infra;

import io.loyaltyhub.member.api.ReferralViews.ReferralLink;
import io.loyaltyhub.member.api.ReferralViews.TopReferrer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Legami di referral sulla tabella {@code member} (docs/servizi/member-service.md §2): {@code referred_by} è
 * l'invitante, {@code referral_completed_at} il completamento. Nessuna tabella dedicata.
 */
@Repository
public class ReferralRepository {

    private static final String LINK_SELECT = """
            SELECT r.id AS referrer_id, r.first_name AS referrer_first, r.last_name AS referrer_last,
                   r.nickname AS referrer_nickname,
                   e.id AS referee_id, e.first_name AS referee_first, e.last_name AS referee_last,
                   e.nickname AS referee_nickname, e.status AS referee_status,
                   e.registered_at, e.referral_completed_at
            FROM member e JOIN member r ON r.id = e.referred_by
            """;

    private final JdbcClient jdbc;

    public ReferralRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Completa il referral dell'invitato in modo atomico: solo se ha un invitante e non è già completato.
     * Ritorna l'invitante se questa chiamata ha completato il legame (due azioni concorrenti: una sola vince).
     */
    public Optional<String> complete(String refereeId, Instant at) {
        return jdbc.sql("""
                        UPDATE member SET referral_completed_at = ?, version = version + 1
                        WHERE id = ? AND referred_by IS NOT NULL AND referral_completed_at IS NULL
                        RETURNING referred_by
                        """)
                .params(Timestamp.from(at), refereeId)
                .query(String.class).optional();
    }

    public long countInvited() {
        return jdbc.sql("SELECT count(*) FROM member WHERE referred_by IS NOT NULL").query(Long.class).single();
    }

    public long countCompleted() {
        return jdbc.sql("SELECT count(*) FROM member WHERE referred_by IS NOT NULL AND referral_completed_at IS NOT NULL")
                .query(Long.class).single();
    }

    public List<TopReferrer> topReferrers(int limit) {
        return jdbc.sql("""
                        SELECT r.id, r.first_name, r.last_name, r.nickname,
                               count(*) AS invited, count(e.referral_completed_at) AS completed
                        FROM member e JOIN member r ON r.id = e.referred_by
                        GROUP BY r.id, r.first_name, r.last_name, r.nickname
                        ORDER BY completed DESC, invited DESC, r.id
                        LIMIT ?
                        """)
                .param(limit)
                .query((rs, n) -> new TopReferrer(rs.getString("id"),
                        name(rs.getString("first_name"), rs.getString("last_name"), rs.getString("nickname"), rs.getString("id")),
                        rs.getLong("invited"), rs.getLong("completed")))
                .list();
    }

    /** Legami più recenti (per registrazione dell'invitato), per la tabella di BO-17. */
    public List<ReferralLink> recentLinks(int limit) {
        return jdbc.sql(LINK_SELECT + " ORDER BY e.registered_at DESC, e.id DESC LIMIT ?")
                .param(limit).query(ReferralRepository::mapLink).list();
    }

    /** Invitati di un membro, dal più recente. */
    public List<ReferralLink> invitedBy(String referrerId) {
        return jdbc.sql(LINK_SELECT + " WHERE e.referred_by = ? ORDER BY e.registered_at DESC, e.id DESC")
                .param(referrerId).query(ReferralRepository::mapLink).list();
    }

    private static ReferralLink mapLink(ResultSet rs, int n) throws SQLException {
        Timestamp completed = rs.getTimestamp("referral_completed_at");
        Timestamp registered = rs.getTimestamp("registered_at");
        return new ReferralLink(
                rs.getString("referrer_id"),
                name(rs.getString("referrer_first"), rs.getString("referrer_last"), rs.getString("referrer_nickname"), rs.getString("referrer_id")),
                rs.getString("referee_id"),
                name(rs.getString("referee_first"), rs.getString("referee_last"), rs.getString("referee_nickname"), rs.getString("referee_id")),
                rs.getString("referee_nickname"),
                rs.getString("referee_status"),
                completed != null ? "COMPLETED" : "PENDING",
                registered != null ? registered.toInstant() : null,
                completed != null ? completed.toInstant() : null);
    }

    private static String name(String first, String last, String nickname, String id) {
        if (first != null && last != null) {
            return first + " " + last;
        }
        return nickname != null ? nickname : id;
    }
}
