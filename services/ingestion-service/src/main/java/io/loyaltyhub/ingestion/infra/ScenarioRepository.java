package io.loyaltyhub.ingestion.infra;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.ingestion.domain.Scenario;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Scenari demo (docs/servizi/ingestion-service.md §2, §6). Caricati dal seed col profilo {@code demo}. */
@Repository
public class ScenarioRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public ScenarioRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<Scenario> findAll() {
        return jdbc.sql("SELECT * FROM scenario ORDER BY code").query(this::map).list();
    }

    public Optional<Scenario> findByCode(String code) {
        return jdbc.sql("SELECT * FROM scenario WHERE code = ?").param(code).query(this::map).optional();
    }

    public void upsert(Scenario s) {
        jdbc.sql("""
                        INSERT INTO scenario (code, name, description, protagonist, watch, steps)
                        VALUES (?, ?, ?, ?, ?, cast(? AS jsonb))
                        ON CONFLICT (code) DO UPDATE SET
                          name = excluded.name, description = excluded.description,
                          protagonist = excluded.protagonist, watch = excluded.watch, steps = excluded.steps
                        """)
                .params(s.code(), s.name(), s.description(), s.protagonist(), s.watch(),
                        s.steps() == null ? "[]" : mapper.writeValueAsString(s.steps()))
                .update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM scenario").update();
    }

    private Scenario map(ResultSet rs, int n) throws SQLException {
        return new Scenario(rs.getString("code"), rs.getString("name"), rs.getString("description"),
                rs.getString("protagonist"), rs.getString("watch"), mapper.readTree(rs.getString("steps")));
    }
}
