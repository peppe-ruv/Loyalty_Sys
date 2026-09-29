package io.loyaltyhub.ingestion.infra;

import io.loyaltyhub.ingestion.domain.MemberRef;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Indice membri per la risoluzione in ingresso (docs/servizi/ingestion-service.md §2, §5.7). Ogni query ha il testo SQL
 * costante nel punto di chiamata (regola 19, ADR-042).
 */
@Repository
public class MemberIndexRepository {

    private final JdbcClient jdbc;

    public MemberIndexRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<MemberRef> findByMemberId(String memberId) {
        return jdbc.sql("SELECT member_id, status FROM member_index WHERE member_id = ?").param(memberId)
                .query(MemberIndexRepository::map).optional();
    }

    public Optional<MemberRef> findByExternalId(String externalId) {
        return jdbc.sql("SELECT member_id, status FROM member_index WHERE external_id = ?").param(externalId)
                .query(MemberIndexRepository::map).optional();
    }

    public Optional<MemberRef> findByEmail(String emailLower) {
        return jdbc.sql("SELECT member_id, status FROM member_index WHERE email_lower = ?")
                .param(emailLower.toLowerCase())
                .query(MemberIndexRepository::map).optional();
    }

    private static MemberRef map(ResultSet rs, int n) throws SQLException {
        return new MemberRef(rs.getString("member_id"), rs.getString("status"));
    }

    public void upsert(String memberId, String externalId, String emailLower, String status) {
        jdbc.sql("""
                        INSERT INTO member_index (member_id, external_id, email_lower, status)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (member_id) DO UPDATE SET
                          external_id = excluded.external_id, email_lower = excluded.email_lower, status = excluded.status
                        """)
                .params(memberId, externalId, emailLower == null ? null : emailLower.toLowerCase(), status)
                .update();
    }

    /** Aggiorna solo lo stato (fatto {@code member.status.changed}); crea la riga se il membro non era indicizzato. */
    public void updateStatus(String memberId, String status) {
        jdbc.sql("""
                        INSERT INTO member_index (member_id, status) VALUES (?, ?)
                        ON CONFLICT (member_id) DO UPDATE SET status = excluded.status
                        """)
                .params(memberId, status)
                .update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM member_index").update();
    }
}
