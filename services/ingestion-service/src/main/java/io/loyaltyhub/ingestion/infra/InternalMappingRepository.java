package io.loyaltyhub.ingestion.infra;

import io.loyaltyhub.ingestion.domain.InternalMapping;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Ponte fatti → azioni (docs/servizi/ingestion-service.md §2, docs/05 §7). La tabella usa i nomi brevi del seed:
 * {@code fact_type = "fact.tier.upgraded"}, {@code action_type = "tier.upgraded"}.
 */
@Repository
public class InternalMappingRepository {

    private final JdbcClient jdbc;

    public InternalMappingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Tipo azione (breve) mappato per un fatto (nome breve), se la mappatura esiste ed è abilitata. */
    public Optional<String> actionTypeFor(String factType) {
        return jdbc.sql("SELECT action_type FROM internal_mapping WHERE fact_type = ? AND enabled = true")
                .param(factType).query(String.class).optional();
    }

    public void upsert(String factType, String actionType, boolean enabled) {
        jdbc.sql("""
                        INSERT INTO internal_mapping (fact_type, action_type, enabled)
                        VALUES (?, ?, ?)
                        ON CONFLICT (fact_type) DO UPDATE SET
                          action_type = excluded.action_type, enabled = excluded.enabled
                        """)
                .params(factType, actionType, enabled)
                .update();
    }

    public List<InternalMapping> findAll() {
        return jdbc.sql("SELECT fact_type, action_type, enabled FROM internal_mapping ORDER BY fact_type")
                .query((rs, n) -> new InternalMapping(rs.getString("fact_type"), rs.getString("action_type"), rs.getBoolean("enabled")))
                .list();
    }

    public Optional<InternalMapping> find(String factType) {
        return jdbc.sql("SELECT fact_type, action_type, enabled FROM internal_mapping WHERE fact_type = ?")
                .param(factType)
                .query((rs, n) -> new InternalMapping(rs.getString("fact_type"), rs.getString("action_type"), rs.getBoolean("enabled")))
                .optional();
    }

    public void setEnabled(String factType, boolean enabled) {
        jdbc.sql("UPDATE internal_mapping SET enabled = ? WHERE fact_type = ?").params(enabled, factType).update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM internal_mapping").update();
    }
}
