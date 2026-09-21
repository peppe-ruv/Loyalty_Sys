package io.loyaltyhub.insight.infra;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.insight.domain.AuditRecord;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Voci di audit (docs/servizi/insight-service.md §2, §3). Inserimento idempotente su {@code event_id}
 * ({@code ON CONFLICT DO NOTHING}); ricerca con filtri e dettaglio per {@code id}. Retention 180 giorni (§5).
 */
@Repository
public class AuditRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public AuditRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Inserisce una voce; {@code false} se {@code event_id} era già presente (duplicato, non ri-registrato). */
    public boolean insert(AuditRecord a) {
        int rows = jdbc.sql("""
                        INSERT INTO audit_entry
                          (id, event_id, at, actor_role, actor_name, service, entity_type, entity_id,
                           action, summary, before, after, correlation_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? AS jsonb), cast(? AS jsonb), ?)
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .params(a.id(), a.eventId(), a.at() == null ? null : Timestamp.from(a.at()),
                        a.actorRole(), a.actorName(), a.service(),
                        a.entityType(), a.entityId(), a.action(), a.summary(),
                        json(a.before()), json(a.after()), a.correlationId())
                .update();
        return rows > 0;
    }

    public Optional<AuditRecord> findById(String id) {
        return jdbc.sql("SELECT * FROM audit_entry WHERE id = ?").param(id).query(this::map).optional();
    }

    /** Ricerca con filtri opzionali (docs §3), ordinata dalla più recente. */
    public List<AuditRecord> search(String actor, String role, String service, String entityType,
                                    String entityId, String action, Instant from, Instant to, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM audit_entry WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendEq(sql, args, "actor_name", actor);
        appendEq(sql, args, "actor_role", role);
        appendEq(sql, args, "service", service);
        appendEq(sql, args, "entity_type", entityType);
        appendEq(sql, args, "entity_id", entityId);
        appendEq(sql, args, "action", action);
        if (from != null) {
            sql.append(" AND at >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND at <= ?");
            args.add(Timestamp.from(to));
        }
        sql.append(" ORDER BY at DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.sql(sql.toString()).params(args).query(this::map).list();
    }

    public long count(String actor, String role, String service, String entityType,
                      String entityId, String action, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM audit_entry WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendEq(sql, args, "actor_name", actor);
        appendEq(sql, args, "actor_role", role);
        appendEq(sql, args, "service", service);
        appendEq(sql, args, "entity_type", entityType);
        appendEq(sql, args, "entity_id", entityId);
        appendEq(sql, args, "action", action);
        if (from != null) {
            sql.append(" AND at >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND at <= ?");
            args.add(Timestamp.from(to));
        }
        return jdbc.sql(sql.toString()).params(args).query(Long.class).single();
    }

    public int deleteOlderThan(int days) {
        return jdbc.sql("DELETE FROM audit_entry WHERE at < now() - make_interval(days => ?)")
                .param(days).update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM audit_entry").update();
    }

    private static void appendEq(StringBuilder sql, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) {
            sql.append(" AND ").append(column).append(" = ?");
            args.add(value);
        }
    }

    private String json(JsonNode node) {
        return node == null || node.isNull() ? null : mapper.writeValueAsString(node);
    }

    private AuditRecord map(ResultSet rs, int n) throws SQLException {
        java.sql.Timestamp at = rs.getTimestamp("at");
        return new AuditRecord(
                rs.getString("id"), rs.getString("event_id"),
                at == null ? Instant.EPOCH : at.toInstant(),
                rs.getString("actor_role"), rs.getString("actor_name"), rs.getString("service"),
                rs.getString("entity_type"), rs.getString("entity_id"), rs.getString("action"),
                rs.getString("summary"), tree(rs.getString("before")), tree(rs.getString("after")),
                rs.getString("correlation_id"));
    }

    private JsonNode tree(String raw) {
        return raw == null ? null : mapper.readTree(raw);
    }
}
