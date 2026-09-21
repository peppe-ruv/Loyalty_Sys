package io.loyaltyhub.wallet.infra;

import io.loyaltyhub.wallet.domain.PointsLot;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Lotti di punti (docs/servizi/wallet-service.md §2). Ogni accredito crea un lotto; i {@code PENDING} vengono
 * rilasciati quando {@code available_at} è raggiunto, gli {@code ACTIVE} scadono a {@code expires_at}. Il
 * consumo FIFO (con {@code lot_consumption}) arriva con la spesa in M4.
 */
@Repository
public class PointsLotRepository {

    private final JdbcClient jdbc;

    public PointsLotRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(PointsLot lot) {
        jdbc.sql("""
                        INSERT INTO points_lot
                          (id, member_id, currency, amount, remaining, status, earned_at, available_at,
                           expires_at, ledger_entry_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(lot.id(), lot.memberId(), lot.currency(), lot.amount(), lot.remaining(), lot.status(),
                        ts(lot.earnedAt()), ts(lot.availableAt()), ts(lot.expiresAt()), lot.ledgerEntryId())
                .update();
    }

    /** Lotti non esauriti di un membro, per scadenza (GET /v1/wallets/{id}/lots). */
    public List<PointsLot> findOpenByMember(String memberId) {
        return jdbc.sql("""
                        SELECT * FROM points_lot
                        WHERE member_id = ? AND status IN ('PENDING', 'ACTIVE') AND remaining > 0
                        ORDER BY expires_at ASC NULLS LAST, earned_at ASC
                        """)
                .param(memberId).query(PointsLotRepository::map).list();
    }

    /** Lotti {@code PENDING} il cui {@code available_at} è arrivato (rilascio, docs §5). */
    public List<PointsLot> findDuePending(Instant asOf) {
        return jdbc.sql("""
                        SELECT * FROM points_lot
                        WHERE status = 'PENDING' AND available_at IS NOT NULL AND available_at <= ?
                        ORDER BY member_id, currency, earned_at
                        """)
                .param(ts(asOf)).query(PointsLotRepository::map).list();
    }

    /** Promuove un lotto {@code PENDING} ad {@code ACTIVE} (rilascio). */
    public void markActive(String lotId) {
        jdbc.sql("UPDATE points_lot SET status = 'ACTIVE' WHERE id = ? AND status = 'PENDING'")
                .param(lotId).update();
    }

    /**
     * Somma dei {@code remaining} dei lotti {@code ACTIVE} in scadenza entro {@code until} e prima scadenza
     * (docs §3, campo {@code expiringSoon}). {@code from} esclude i già scaduti non ancora spazzati.
     */
    public Optional<ExpiringSoon> expiringSoon(String memberId, String currency, Instant from, Instant until) {
        return jdbc.sql("""
                        SELECT coalesce(sum(remaining), 0) AS amount, min(expires_at) AS next_expiry
                        FROM points_lot
                        WHERE member_id = ? AND currency = ? AND status = 'ACTIVE' AND remaining > 0
                          AND expires_at IS NOT NULL AND expires_at > ? AND expires_at <= ?
                        """)
                .params(memberId, currency, ts(from), ts(until))
                .query((rs, n) -> {
                    long amount = rs.getLong("amount");
                    Timestamp next = rs.getTimestamp("next_expiry");
                    return new ExpiringSoon(amount, next == null ? null : next.toInstant());
                })
                .optional();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM points_lot").update();
    }

    public record ExpiringSoon(long amount, Instant nextExpiryAt) {
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static PointsLot map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        Timestamp available = rs.getTimestamp("available_at");
        Timestamp expires = rs.getTimestamp("expires_at");
        Timestamp earned = rs.getTimestamp("earned_at");
        return new PointsLot(
                rs.getString("id"), rs.getString("member_id"), rs.getString("currency"),
                rs.getLong("amount"), rs.getLong("remaining"), rs.getString("status"),
                earned == null ? null : earned.toInstant(),
                available == null ? null : available.toInstant(),
                expires == null ? null : expires.toInstant(),
                rs.getString("ledger_entry_id"));
    }
}
