package io.loyaltyhub.ingestion.infra;

import io.loyaltyhub.ingestion.domain.EventType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class EventTypeRepository {

    private final JdbcClient jdbc;

    public EventTypeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<EventType> findByCode(String code) {
        return jdbc.sql("""
                        SELECT code, name, origin, category, data_schema::text AS data_schema, enabled, icon
                        FROM event_type WHERE code = ?
                        """)
                .param(code)
                .query((rs, n) -> new EventType(
                        rs.getString("code"), rs.getString("name"), rs.getString("origin"),
                        rs.getString("category"), rs.getString("data_schema"),
                        rs.getBoolean("enabled"), rs.getString("icon")))
                .optional();
    }

    public void upsert(String code, String name, String origin, String category,
                       String dataSchemaJson, String sampleDataJson, boolean enabled, String icon) {
        jdbc.sql("""
                        INSERT INTO event_type (code, name, origin, category, data_schema, sample_data, enabled, icon)
                        VALUES (?, ?, ?, ?, cast(? AS jsonb), cast(? AS jsonb), ?, ?)
                        ON CONFLICT (code) DO UPDATE SET
                          name = excluded.name, origin = excluded.origin, category = excluded.category,
                          data_schema = excluded.data_schema, sample_data = excluded.sample_data,
                          enabled = excluded.enabled, icon = excluded.icon
                        """)
                .params(code, name, origin, category, dataSchemaJson, sampleDataJson, enabled, icon)
                .update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM event_type").update();
    }
}
