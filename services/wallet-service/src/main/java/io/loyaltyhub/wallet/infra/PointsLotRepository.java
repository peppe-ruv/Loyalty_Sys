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

    /** Lotti attivi di un membro per l'addebito, ordinati per scadenza crescente (null ultimi) poi earned_at. */
    public List<PointsLot> findActiveForDebit(String memberId, String currency) {
        return jdbc.sql("""
                        SELECT * FROM points_lot
                        WHERE member_id = ? AND currency = ? AND status = 'ACTIVE' AND remaining > 0
                        ORDER BY expires_at ASC NULLS LAST, earned_at ASC
                        """)
                .params(memberId, currency).query(PointsLotRepository::map).list();
    }

    public void consumeLot(String lotId, long amount, String status) {
        jdbc.sql("UPDATE points_lot SET remaining = remaining - ?, status = ? WHERE id = ?")
                .params(amount, status, lotId).update();
    }

    public void insertConsumption(String ledgerEntryId, String lotId, long amount) {
        jdbc.sql("INSERT INTO lot_consumption (ledger_entry_id, lot_id, amount) VALUES (?, ?, ?)")
                .params(ledgerEntryId, lotId, amount).update();
    }

    /**
     * Consuma {@code amount} dai lotti attivi in ordine FIFO — prima scadenza, poi anzianità (docs/03 §4, F-WAL-04) —
     * e registra in {@code lot_consumption} quanto preso da ciascun lotto per il movimento {@code ledgerEntryId}.
     * Il chiamante ha già verificato il saldo sotto lock del wallet.
     */
    public void consumeFifo(String memberId, String currency, long amount, String ledgerEntryId) {
        long left = amount;
        for (PointsLot lot : findActiveForDebit(memberId, currency)) {
            if (left <= 0) {
                break;
            }
            long take = Math.min(lot.remaining(), left);
            left -= take;
            consumeLot(lot.id(), take, lot.remaining() - take == 0 ? PointsLot.EXHAUSTED : PointsLot.ACTIVE);
            insertConsumption(ledgerEntryId, lot.id(), take);
        }
    }

    /** Quanto un movimento ha preso da ciascun lotto (per il rimborso). */
    public List<Consumption> consumptions(String ledgerEntryId) {
        return jdbc.sql("""
                        SELECT c.lot_id, c.amount, l.status, l.expires_at FROM lot_consumption c
                        JOIN points_lot l ON l.id = c.lot_id WHERE c.ledger_entry_id = ?
                        """)
                .param(ledgerEntryId)
                .query((rs, n) -> new Consumption(rs.getString("lot_id"), rs.getLong("amount"), rs.getString("status"),
                        rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant()))
                .list();
    }

    /** Restituisce punti a un lotto non scaduto: torna {@code ACTIVE}. */
    public void restore(String lotId, long amount) {
        jdbc.sql("UPDATE points_lot SET remaining = remaining + ?, status = 'ACTIVE' WHERE id = ?")
                .params(amount, lotId).update();
    }

    public record Consumption(String lotId, long amount, String status, Instant expiresAt) {
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

    /** Lotti {@code ACTIVE} con {@code remaining > 0} scaduti a {@code asOf} (job scadenze, docs §5). */
    public List<PointsLot> findActiveExpired(Instant asOf) {
        return jdbc.sql("""
                        SELECT * FROM points_lot
                        WHERE status = 'ACTIVE' AND remaining > 0
                          AND expires_at IS NOT NULL AND expires_at <= ?
                        ORDER BY member_id, currency, expires_at
                        """)
                .param(ts(asOf)).query(PointsLotRepository::map).list();
    }

    /** Segna un lotto {@code EXPIRED} azzerando il {@code remaining} (job scadenze). */
    public void markExpired(String lotId) {
        jdbc.sql("UPDATE points_lot SET status = 'EXPIRED', remaining = 0 WHERE id = ? AND status = 'ACTIVE'")
                .param(lotId).update();
    }

    /** Lotti {@code ACTIVE} non ancora preavvisati in scadenza tra {@code asOf} e {@code until} (job preavvisi). */
    public List<PointsLot> findWarnable(Instant asOf, Instant until) {
        return jdbc.sql("""
                        SELECT * FROM points_lot
                        WHERE status = 'ACTIVE' AND remaining > 0 AND warned = false
                          AND expires_at IS NOT NULL AND expires_at > ? AND expires_at <= ?
                        ORDER BY member_id, currency, expires_at
                        """)
                .params(ts(asOf), ts(until)).query(PointsLotRepository::map).list();
    }

    /** Segna un lotto come già preavvisato (una volta per lotto, docs §5). */
    public void markWarned(String lotId) {
        jdbc.sql("UPDATE points_lot SET warned = true WHERE id = ?").param(lotId).update();
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
