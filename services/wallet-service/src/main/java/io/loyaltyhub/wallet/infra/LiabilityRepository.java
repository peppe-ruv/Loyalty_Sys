package io.loyaltyhub.wallet.infra;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Passività (F-WAL-09): punti in circolazione per valuta, dai saldi dei wallet, e ripartizione per mese di scadenza
 * dai lotti {@code ACTIVE} (mese in {@code Europe/Rome}; {@code null} = lotti senza scadenza).
 */
@Repository
public class LiabilityRepository {

    public record Totals(long outstanding, long pending) {
    }

    public record MonthAmount(String month, long amount) {
    }

    private final JdbcClient jdbc;

    public LiabilityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Totals totals(String currency) {
        return jdbc.sql("""
                        SELECT coalesce(sum(balance_active), 0) AS outstanding, coalesce(sum(balance_pending), 0) AS pending
                        FROM wallet WHERE currency = ?
                        """)
                .param(currency)
                .query((rs, n) -> new Totals(rs.getLong("outstanding"), rs.getLong("pending")))
                .single();
    }

    public List<MonthAmount> byExpiryMonth(String currency) {
        return jdbc.sql("""
                        SELECT to_char(expires_at AT TIME ZONE 'Europe/Rome', 'YYYY-MM') AS month, sum(remaining) AS amount
                        FROM points_lot
                        WHERE currency = ? AND status = 'ACTIVE' AND remaining > 0
                        GROUP BY 1
                        ORDER BY 1 NULLS LAST
                        """)
                .param(currency)
                .query((rs, n) -> new MonthAmount(rs.getString("month"), rs.getLong("amount")))
                .list();
    }
}
