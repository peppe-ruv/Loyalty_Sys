package io.loyaltyhub.ingestion.infra;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.ingestion.domain.ScenarioRun;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** Esecuzioni di scenario (docs/servizi/ingestion-service.md §2, §3). L'avanzamento è aggiornato dall'esecutore. */
@Repository
public class ScenarioRunRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public ScenarioRunRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void insert(String id, String scenarioCode, Instant startedAt, int stepsTotal, String actor) {
        jdbc.sql("""
                        INSERT INTO scenario_run
                          (id, scenario_code, started_at, status, steps_total, steps_done, actor, results)
                        VALUES (?, ?, ?, 'RUNNING', ?, 0, ?, '[]')
                        """)
                .params(id, scenarioCode, Timestamp.from(startedAt), stepsTotal, actor)
                .update();
    }

    /** Avanzamento intermedio: numero di passi fatti e array degli esiti finora. */
    public void updateProgress(String id, int stepsDone, String resultsJson) {
        jdbc.sql("UPDATE scenario_run SET steps_done = ?, results = cast(? AS jsonb) WHERE id = ?")
                .params(stepsDone, resultsJson, id).update();
    }

    public void finish(String id, String status, Instant finishedAt, String resultsJson) {
        jdbc.sql("""
                        UPDATE scenario_run
                        SET status = ?, finished_at = ?, results = cast(? AS jsonb)
                        WHERE id = ?
                        """)
                .params(status, Timestamp.from(finishedAt), resultsJson, id).update();
    }

    public Optional<ScenarioRun> findById(String id) {
        return jdbc.sql("SELECT * FROM scenario_run WHERE id = ?").param(id).query(this::map).optional();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM scenario_run").update();
    }

    private ScenarioRun map(ResultSet rs, int n) throws SQLException {
        Timestamp started = rs.getTimestamp("started_at");
        Timestamp finished = rs.getTimestamp("finished_at");
        return new ScenarioRun(
                rs.getString("id"), rs.getString("scenario_code"),
                started == null ? Instant.EPOCH : started.toInstant(),
                finished == null ? null : finished.toInstant(),
                rs.getString("status"), rs.getInt("steps_total"), rs.getInt("steps_done"),
                rs.getString("actor"), mapper.readTree(rs.getString("results")));
    }
}
