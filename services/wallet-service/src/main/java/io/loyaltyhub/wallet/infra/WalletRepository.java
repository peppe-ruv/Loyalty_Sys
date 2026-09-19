package io.loyaltyhub.wallet.infra;

import io.loyaltyhub.wallet.domain.WalletBalance;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Saldi dei wallet (docs/servizi/wallet-service.md §2, §5). Ogni operazione prende il lock di riga. */
@Repository
public class WalletRepository {

    private final JdbcClient jdbc;

    public WalletRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Crea il wallet se assente (idempotente): usato all'arrivo di {@code member.registered} o "on the fly". */
    public void ensureExists(String memberId, String currency) {
        jdbc.sql("""
                        INSERT INTO wallet (member_id, currency) VALUES (?, ?)
                        ON CONFLICT (member_id, currency) DO NOTHING
                        """)
                .params(memberId, currency).update();
    }

    /** Blocca la riga del wallet per serializzare le operazioni sul membro/valuta (docs §5). */
    public void lock(String memberId, String currency) {
        jdbc.sql("SELECT 1 FROM wallet WHERE member_id = ? AND currency = ? FOR UPDATE")
                .params(memberId, currency).query(Integer.class).optional();
    }

    public Optional<WalletBalance> find(String memberId, String currency) {
        return jdbc.sql("SELECT * FROM wallet WHERE member_id = ? AND currency = ?")
                .params(memberId, currency).query(WalletRepository::map).optional();
    }

    public List<WalletBalance> findByMember(String memberId) {
        return jdbc.sql("SELECT * FROM wallet WHERE member_id = ? ORDER BY currency").param(memberId)
                .query(WalletRepository::map).list();
    }

    /** Accredita punti attivi: {@code balance_active += amount}, {@code lifetime_earned += amount}. */
    public long credit(String memberId, String currency, long amount) {
        return jdbc.sql("""
                        UPDATE wallet SET balance_active = balance_active + ?,
                          lifetime_earned = lifetime_earned + ?, updated_at = now()
                        WHERE member_id = ? AND currency = ?
                        RETURNING balance_active
                        """)
                .params(amount, amount, memberId, currency).query(Long.class).single();
    }

    /** Imposta i saldi (seed): {@code balance_active} e {@code lifetime_earned}. */
    public void setBalance(String memberId, String currency, long active, long lifetimeEarned) {
        jdbc.sql("""
                        INSERT INTO wallet (member_id, currency, balance_active, lifetime_earned)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (member_id, currency) DO UPDATE SET
                          balance_active = excluded.balance_active,
                          lifetime_earned = excluded.lifetime_earned, updated_at = now()
                        """)
                .params(memberId, currency, active, lifetimeEarned).update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM wallet").update();
    }

    private static WalletBalance map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new WalletBalance(
                rs.getString("member_id"), rs.getString("currency"), rs.getLong("balance_active"),
                rs.getLong("balance_pending"), rs.getLong("lifetime_earned"),
                rs.getLong("lifetime_spent"), rs.getLong("lifetime_expired"));
    }
}
