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
        jdbc.sql("""
                        INSERT INTO ledger_entry
                          (id, member_id, currency, type, amount, direction, balance_after, occurred_at,
                           source_type, effect_id, campaign_code, action_id, correlation_id, description, actor, metadata)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? AS jsonb))
                        """)
                .params(e.id(), e.memberId(), e.currency(), e.type(), e.amount(), e.direction(), e.balanceAfter(),
                        java.sql.Timestamp.from(e.occurredAt()), e.sourceType(), effectId, e.campaignCode(),
                        actionId, correlationId, e.description(), actor,
                        e.metadataJson() == null ? "{}" : e.metadataJson())
                .update();
    }

    public List<LedgerEntry> listByMember(String memberId, String currency, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ledger_entry WHERE member_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(memberId);
        if (currency != null && !currency.isBlank()) {
            sql.append(" AND currency = ?");
            args.add(currency.trim().toUpperCase());
        }
        sql.append(" ORDER BY occurred_at DESC, created_at DESC LIMIT ?");
        args.add(limit);
        return jdbc.sql(sql.toString()).params(args).query(LedgerRepository::map).list();
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
