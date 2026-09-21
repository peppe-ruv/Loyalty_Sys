package io.loyaltyhub.wallet.infra;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Valute del programma (docs/servizi/wallet-service.md §2). */
@Repository
public class CurrencyRepository {

    private final JdbcClient jdbc;

    public CurrencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(String code, String name, boolean spendable, String expiryPolicyJson) {
        jdbc.sql("""
                        INSERT INTO currency (code, name, spendable, expiry_policy)
                        VALUES (?, ?, ?, cast(? AS jsonb))
                        ON CONFLICT (code) DO UPDATE SET
                          name = excluded.name, spendable = excluded.spendable, expiry_policy = excluded.expiry_policy
                        """)
                .params(code, name, spendable, expiryPolicyJson == null ? "{}" : expiryPolicyJson).update();
    }

    public List<CurrencyRow> findAll() {
        return jdbc.sql("SELECT code, name, spendable, expiry_policy::text AS expiry_policy FROM currency ORDER BY code")
                .query((rs, n) -> new CurrencyRow(rs.getString("code"), rs.getString("name"),
                        rs.getBoolean("spendable"), rs.getString("expiry_policy")))
                .list();
    }

    public java.util.Optional<CurrencyRow> findByCode(String code) {
        return jdbc.sql("SELECT code, name, spendable, expiry_policy::text AS expiry_policy FROM currency WHERE code = ?")
                .param(code)
                .query((rs, n) -> new CurrencyRow(rs.getString("code"), rs.getString("name"),
                        rs.getBoolean("spendable"), rs.getString("expiry_policy")))
                .optional();
    }

    public record CurrencyRow(String code, String name, boolean spendable, String expiryPolicyJson) {
    }
}
