package io.loyaltyhub.wallet.infra;

import io.loyaltyhub.wallet.domain.TierHistory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Storico dei passaggi di livello (docs/servizi/wallet-service.md §2, F-TIER-06). */
@Repository
public class TierHistoryRepository {

    private final JdbcClient jdbc;

    public TierHistoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(TierHistory h) {
        jdbc.sql("""
                        INSERT INTO tier_history (id, member_id, from_tier, to_tier, kind, edition_code, at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(h.id(), h.memberId(), h.fromTier(), h.toTier(), h.kind(), h.editionCode(),
                        Timestamp.from(h.at()))
                .update();
    }

    public List<TierHistory> findByMember(String memberId) {
        return jdbc.sql("SELECT * FROM tier_history WHERE member_id = ? ORDER BY at DESC")
                .param(memberId).query(TierHistoryRepository::map).list();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM tier_history").update();
    }

    private static TierHistory map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        Timestamp at = rs.getTimestamp("at");
        return new TierHistory(rs.getString("id"), rs.getString("member_id"), rs.getString("from_tier"),
                rs.getString("to_tier"), rs.getString("kind"), rs.getString("edition_code"),
                at == null ? Instant.EPOCH : at.toInstant());
    }
}
