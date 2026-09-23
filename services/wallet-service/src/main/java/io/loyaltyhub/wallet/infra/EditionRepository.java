package io.loyaltyhub.wallet.infra;

import io.loyaltyhub.wallet.domain.Edition;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Edizioni annuali (docs/servizi/wallet-service.md §2). Chiusura e downgrade: M3. */
@Repository
public class EditionRepository {

    private final JdbcClient jdbc;

    public EditionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(String code, String name, LocalDate start, LocalDate end, LocalDate grace, String status) {
        jdbc.sql("""
                        INSERT INTO edition (code, name, start_date, end_date, redemption_grace_until, status)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (code) DO UPDATE SET
                          name = excluded.name, start_date = excluded.start_date, end_date = excluded.end_date,
                          redemption_grace_until = excluded.redemption_grace_until, status = excluded.status
                        """)
                .params(code, name, start, end, grace, status).update();
    }

    public List<Edition> findAll() {
        return jdbc.sql("SELECT code, name, start_date, end_date, redemption_grace_until, status FROM edition ORDER BY start_date")
                .query(EditionRepository::map)
                .list();
    }

    public Optional<Edition> findByCode(String code) {
        return jdbc.sql("SELECT code, name, start_date, end_date, redemption_grace_until, status FROM edition WHERE code = ?")
                .param(code)
                .query(EditionRepository::map)
                .optional();
    }

    /**
     * Legge l'edizione bloccandone la riga fino al commit ({@code FOR UPDATE}): serializza le chiusure concorrenti
     * della stessa edizione — la seconda aspetta e poi trova {@code CLOSED}.
     */
    public Optional<Edition> lockByCode(String code) {
        return jdbc.sql("SELECT code, name, start_date, end_date, redemption_grace_until, status FROM edition WHERE code = ? FOR UPDATE")
                .param(code)
                .query(EditionRepository::map)
                .optional();
    }

    /** Edizione il cui periodo contiene {@code day} (estremi inclusi); vuoto se nessuna lo copre. */
    public Optional<Edition> findContaining(LocalDate day) {
        return jdbc.sql("""
                        SELECT code, name, start_date, end_date, redemption_grace_until, status FROM edition
                        WHERE start_date <= ? AND (end_date IS NULL OR end_date >= ?)
                        ORDER BY start_date DESC LIMIT 1
                        """)
                .params(day, day)
                .query(EditionRepository::map)
                .optional();
    }

    public void updateStatus(String code, String status) {
        jdbc.sql("UPDATE edition SET status = ? WHERE code = ?")
                .params(status, code).update();
    }

    private static Edition map(ResultSet rs, int rowNum) throws SQLException {
        java.sql.Date end = rs.getDate("end_date");
        java.sql.Date grace = rs.getDate("redemption_grace_until");
        return new Edition(
                rs.getString("code"),
                rs.getString("name"),
                rs.getDate("start_date").toLocalDate(),
                end != null ? end.toLocalDate() : null,
                grace != null ? grace.toLocalDate() : null,
                rs.getString("status")
        );
    }
}
