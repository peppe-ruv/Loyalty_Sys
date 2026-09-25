package io.loyaltyhub.wallet.infra;

import io.loyaltyhub.wallet.domain.LedgerEntry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Libro mastro (docs/servizi/wallet-service.md §2). {@code effect_id} unico = idempotenza degli accrediti. */
@Repository
public class LedgerRepository {

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean existsByEffectId(String effectId) {
        Long n = jdbc.sql("SELECT count(*) FROM ledger_entry WHERE effect_id = ?").param(effectId)
                .query(Long.class).single();
        return n > 0;
    }

    public void insert(LedgerEntry e, String effectId, String actionId, String correlationId, String actor) {
        insert(e, effectId, actionId, correlationId, actor, null);
    }

    /** Come {@link #insert(LedgerEntry, String, String, String, String)} con il riferimento alla richiesta premio. */
    public void insert(LedgerEntry e, String effectId, String actionId, String correlationId, String actor,
                       String redemptionId) {
        jdbc.sql("""
                        INSERT INTO ledger_entry
                          (id, member_id, currency, type, amount, direction, balance_after, occurred_at,
                           source_type, effect_id, redemption_id, campaign_code, action_id, correlation_id, description,
                           actor, metadata)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? AS jsonb))
                        """)
                .params(e.id(), e.memberId(), e.currency(), e.type(), e.amount(), e.direction(), e.balanceAfter(),
                        java.sql.Timestamp.from(e.occurredAt()), e.sourceType(), effectId, redemptionId,
                        e.campaignCode(), actionId, correlationId, e.description(), actor,
                        e.metadataJson() == null ? "{}" : e.metadataJson())
                .update();
    }

    /** Movimento di una richiesta premio per tipo ({@code SPEND}/{@code REFUND}): idempotenza su {@code redemption_id}. */
    public java.util.Optional<RedemptionEntry> findByRedemption(String redemptionId, String type) {
        return jdbc.sql("""
                        SELECT id, member_id, currency, amount FROM ledger_entry
                        WHERE redemption_id = ? AND type = ? ORDER BY created_at LIMIT 1
                        """)
                .params(redemptionId, type)
                .query((rs, n) -> new RedemptionEntry(rs.getString("id"), rs.getString("member_id"),
                        rs.getString("currency"), rs.getLong("amount")))
                .optional();
    }

    public record RedemptionEntry(String id, String memberId, String currency, long amount) {
    }

    public List<LedgerEntry> listByMember(String memberId, String currency, int limit) {
        return search(memberId, new LedgerFilter(currency, null, null, null), limit).stream()
                .map(LedgerLine::entry).toList();
    }

    /**
     * Filtri del libro mastro (wallet-service §3: {@code currency, type, from, to}). Tutti facoltativi; {@code types}
     * vuoto = tutti i tipi; {@code from}/{@code to} inclusi, sulla data di business {@code occurred_at}.
     */
    public record LedgerFilter(String currency, List<String> types, Instant from, Instant to) {
    }

    /**
     * Movimento con i riferimenti salvati ma non nel record di dominio (F-WAL-02: azione e attore) e con il lotto
     * nato dal movimento, se c'è (PT-07: stato in attesa e scadenza del lotto).
     */
    public record LedgerLine(LedgerEntry entry, String actionId, String actor, String lotStatus, Instant lotExpiresAt) {
    }

    public List<LedgerLine> search(String memberId, LedgerFilter filter, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT e.*, lot.status AS lot_status, lot.expires_at AS lot_expires_at
                FROM ledger_entry e
                LEFT JOIN LATERAL (
                    SELECT l.status, l.expires_at FROM points_lot l
                    WHERE l.member_id = e.member_id AND l.currency = e.currency AND l.ledger_entry_id = e.id
                    ORDER BY l.id LIMIT 1
                ) lot ON true
                WHERE e.member_id = ?""");
        List<Object> args = new ArrayList<>();
        args.add(memberId);
        if (filter.currency() != null && !filter.currency().isBlank()) {
            sql.append(" AND e.currency = ?");
            args.add(filter.currency().trim().toUpperCase());
        }
        if (filter.types() != null && !filter.types().isEmpty()) {
            sql.append(" AND e.type IN (").append(String.join(", ", java.util.Collections.nCopies(filter.types().size(), "?")))
                    .append(')');
            filter.types().forEach(t -> args.add(t.trim().toUpperCase()));
        }
        if (filter.from() != null) {
            sql.append(" AND e.occurred_at >= ?");
            args.add(java.sql.Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND e.occurred_at <= ?");
            args.add(java.sql.Timestamp.from(filter.to()));
        }
        sql.append(" ORDER BY e.occurred_at DESC, e.created_at DESC LIMIT ?");
        args.add(limit);
        return jdbc.sql(sql.toString()).params(args).query((rs, n) -> {
            java.sql.Timestamp lotExpires = rs.getTimestamp("lot_expires_at");
            return new LedgerLine(map(rs, n), rs.getString("action_id"), rs.getString("actor"),
                    rs.getString("lot_status"), lotExpires == null ? null : lotExpires.toInstant());
        }).list();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM ledger_entry").update();
    }

    private static LedgerEntry map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        java.sql.Timestamp occurred = rs.getTimestamp("occurred_at");
        return new LedgerEntry(
                rs.getString("id"), rs.getString("member_id"), rs.getString("currency"), rs.getString("type"),
                rs.getLong("amount"), rs.getString("direction"), rs.getLong("balance_after"),
                occurred == null ? Instant.EPOCH : occurred.toInstant(), rs.getString("source_type"),
                rs.getString("campaign_code"), rs.getString("description"), rs.getString("metadata"));
    }
}
