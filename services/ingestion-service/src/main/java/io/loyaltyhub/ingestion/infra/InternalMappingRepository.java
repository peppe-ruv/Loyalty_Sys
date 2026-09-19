package io.loyaltyhub.ingestion.infra;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Ponte fatti → azioni (docs/servizi/ingestion-service.md §2, docs/05 §7). Consumato in M3. */
@Repository
public class InternalMappingRepository {

    private final JdbcClient jdbc;

    public InternalMappingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Tipo azione mappato per un fatto, se la mappatura esiste ed è abilitata. */
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

    public void deleteAll() {
        jdbc.sql("DELETE FROM internal_mapping").update();
    }
}
