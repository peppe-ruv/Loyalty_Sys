package io.loyaltyhub.engagement.infra;

import io.loyaltyhub.engagement.domain.NotificationRule;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Regole di notifica (docs/servizi/engagement-service.md §2). */
@Repository
public class RuleRepository {

    private static final String COLUMNS =
            "id, code, fact_type, condition::text AS condition, template_code, enabled, version, updated_at, updated_by";

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public RuleRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<NotificationRule> findAll(String factType, String templateCode) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM notification_rule WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (factType != null && !factType.isBlank()) {
            sql.append(" AND fact_type = ?");
            params.add(factType.trim());
        }
        if (templateCode != null && !templateCode.isBlank()) {
            sql.append(" AND template_code = ?");
            params.add(templateCode.trim().toUpperCase());
        }
        sql.append(" ORDER BY fact_type, code");
        return jdbc.sql(sql.toString()).params(params).query(this::map).list();
    }

    /** Regole attive per un tipo di fatto (forma breve), in ordine di codice: deterministico. */
    public List<NotificationRule> findEnabledFor(String factType) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM notification_rule WHERE enabled AND fact_type = ? ORDER BY code")
                .param(factType).query(this::map).list();
    }

    public Optional<NotificationRule> find(String idOrCode) {
        if (idOrCode == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM notification_rule WHERE id = ? OR code = ?")
                .params(idOrCode.trim(), idOrCode.trim().toUpperCase()).query(this::map).optional();
    }

    public void insert(NotificationRule r, String actor) {
        jdbc.sql("""
                        INSERT INTO notification_rule (id, code, fact_type, condition, template_code, enabled, created_by, updated_by)
                        VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                        """)
                .params(r.id(), r.code(), r.factType(), json(r.condition()), r.templateCode(), r.enabled(), actor, actor)
                .update();
    }

    /** Aggiorna se la versione è ancora {@code expectedVersion}; {@code false} = modificata nel frattempo (409). */
    public boolean update(NotificationRule r, long expectedVersion, String actor) {
        return jdbc.sql("""
                        UPDATE notification_rule SET fact_type = ?, condition = ?::jsonb, template_code = ?, enabled = ?,
                          version = version + 1, updated_at = now(), updated_by = ?
                        WHERE id = ? AND version = ?
                        """)
                .params(r.factType(), json(r.condition()), r.templateCode(), r.enabled(), actor, r.id(), expectedVersion)
                .update() == 1;
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM notification_rule").update();
    }

    private String json(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : mapper.writeValueAsString(node);
    }

    private NotificationRule map(ResultSet rs, int n) throws SQLException {
        String condition = rs.getString("condition");
        return new NotificationRule(rs.getString("id"), rs.getString("code"), rs.getString("fact_type"),
                condition == null ? null : mapper.readTree(condition), rs.getString("template_code"), rs.getBoolean("enabled"),
                rs.getLong("version"), rs.getTimestamp("updated_at").toInstant(), rs.getString("updated_by"));
    }
}
