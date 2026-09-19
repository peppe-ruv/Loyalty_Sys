package io.loyaltyhub.wallet.infra;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

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
}
