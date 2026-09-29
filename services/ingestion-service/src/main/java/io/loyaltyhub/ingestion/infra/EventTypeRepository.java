package io.loyaltyhub.ingestion.infra;

import io.loyaltyhub.ingestion.domain.EventType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Tipi azione (BO-09, docs/servizi/ingestion-service.md §2). Solo testo SQL costante (regola 19, ADR-042): codice,
 * {@code data_schema} e {@code sample_data} sono parametri legati. I nomi dei campi di un tipo personalizzato e i loro
 * percorsi ({@code GET /v1/event-types/{code}/fields}) si ricavano in Java dallo schema ({@code SchemaFields}) e non
 * entrano mai in una query; il codice del tipo è comunque validato ({@code EventTypeService.CODE}, Q-439).
 */
@Repository
public class EventTypeRepository {

    private final JdbcClient jdbc;

    public EventTypeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public java.util.List<EventType> findAll() {
        return jdbc.sql("""
                        SELECT code, name, description, origin, category, data_schema::text AS data_schema,
                               sample_data::text AS sample_data, enabled, icon
                        FROM event_type ORDER BY category, code
                        """)
                .query(EventTypeRepository::row)
                .list();
    }

    /** {@code sample_data} del tipo (per il simulatore quando {@code data} non è fornito). */
    public Optional<String> sampleData(String code) {
        return jdbc.sql("SELECT sample_data::text FROM event_type WHERE code = ?").param(code)
                .query(String.class).optional();
    }

    public Optional<EventType> findByCode(String code) {
        return jdbc.sql("""
                        SELECT code, name, description, origin, category, data_schema::text AS data_schema,
                               sample_data::text AS sample_data, enabled, icon
                        FROM event_type WHERE code = ?
                        """)
                .param(code)
                .query(EventTypeRepository::row)
                .optional();
    }

    /**
     * Crea un tipo solo se il codice non esiste (Q-440): {@code true} se la riga è stata inserita, {@code false} se un
     * tipo con lo stesso codice c'era già o è stato inserito da una transazione concorrente (che vince).
     */
    public boolean insertIfAbsent(EventType t) {
        return jdbc.sql("""
                        INSERT INTO event_type (code, name, description, origin, category, data_schema, sample_data, enabled, icon)
                        VALUES (?, ?, ?, ?, ?, cast(? AS jsonb), cast(? AS jsonb), ?, ?)
                        ON CONFLICT (code) DO NOTHING
                        """)
                .params(t.code(), t.name(), t.description(), t.origin(), t.category(), t.dataSchema(), t.sampleData(),
                        t.enabled(), t.icon())
                .update() == 1;
    }

    /** Crea o sostituisce un tipo (modifica da BO-09 con PUT, seed demo): tutti i campi tranne {@code code}. */
    public void save(EventType t) {
        jdbc.sql("""
                        INSERT INTO event_type (code, name, description, origin, category, data_schema, sample_data, enabled, icon)
                        VALUES (?, ?, ?, ?, ?, cast(? AS jsonb), cast(? AS jsonb), ?, ?)
                        ON CONFLICT (code) DO UPDATE SET
                          name = excluded.name, description = excluded.description, origin = excluded.origin,
                          category = excluded.category, data_schema = excluded.data_schema,
                          sample_data = excluded.sample_data, enabled = excluded.enabled, icon = excluded.icon
                        """)
                .params(t.code(), t.name(), t.description(), t.origin(), t.category(), t.dataSchema(), t.sampleData(),
                        t.enabled(), t.icon())
                .update();
    }

    private static EventType row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new EventType(
                rs.getString("code"), rs.getString("name"), rs.getString("description"), rs.getString("origin"),
                rs.getString("category"), rs.getString("data_schema"), rs.getString("sample_data"),
                rs.getBoolean("enabled"), rs.getString("icon"));
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM event_type").update();
    }
}
