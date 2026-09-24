package io.loyaltyhub.campaign.infra;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.campaign.domain.Campaign;
import io.loyaltyhub.campaign.domain.CampaignStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistenza delle campagne (docs/servizi/campaign-service.md §2). I campi jsonb restano {@link JsonNode}. */
@Repository
public class CampaignRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public CampaignRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    private static final String COLS = """
            id, code, name, description, member_description, icon, trigger_action_types,
            audience::text AS audience, conditions::text AS conditions, effects::text AS effects,
            limits::text AS limits, schedule::text AS schedule, priority, exclusive_group,
            visible_in_portal, system, requires_legal, labels, status, version, created_at, updated_at
            """;

    public List<Campaign> findAll() {
        return jdbc.sql("SELECT " + COLS + " FROM campaign").query(this::map).list();
    }

    public Optional<Campaign> findById(String id) {
        return jdbc.sql("SELECT " + COLS + " FROM campaign WHERE id = ?").param(id).query(this::map).optional();
    }

    public Optional<Campaign> findByCode(String code) {
        return jdbc.sql("SELECT " + COLS + " FROM campaign WHERE code = ?").param(code).query(this::map).optional();
    }

    public List<Campaign> search(String status, String actionType, String q) {
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM campaign WHERE 1 = 1");
        List<Object> args = new java.util.ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim().toUpperCase());
        }
        if (actionType != null && !actionType.isBlank()) {
            sql.append(" AND ? = ANY(trigger_action_types)");
            args.add(actionType.trim());
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (code ILIKE ? OR name ILIKE ?)");
            args.add("%" + q.trim() + "%");
            args.add("%" + q.trim() + "%");
        }
        sql.append(" ORDER BY priority DESC, code");
        return jdbc.sql(sql.toString()).params(args).query(this::map).list();
    }

    public void insert(Campaign c) {
        jdbc.sql("""
                        INSERT INTO campaign
                          (id, code, name, description, member_description, icon, trigger_action_types,
                           audience, conditions, effects, limits, schedule, priority, exclusive_group,
                           visible_in_portal, system, requires_legal, labels, status, version)
                        VALUES (?, ?, ?, ?, ?, ?, ?::text[], cast(? AS jsonb), cast(? AS jsonb), cast(? AS jsonb),
                                cast(? AS jsonb), cast(? AS jsonb), ?, ?, ?, ?, ?, ?::text[], ?, ?)
                        """)
                .params(c.id(), c.code(), c.name(), c.description(),
                        c.memberDescription(), c.icon(), TextArrays.literal(c.triggerActionTypes()),
                        c.audience().toString(), c.conditions().toString(), c.effects().toString(),
                        c.limits().toString(), c.schedule().toString(), c.priority(), c.exclusiveGroup(),
                        c.visibleInPortal(), c.system(), c.requiresLegal(), TextArrays.literal(c.labels()),
                        c.status().name(), c.version())
                .update();
    }

    public void updateStatus(String id, CampaignStatus status) {
        jdbc.sql("UPDATE campaign SET status = ?, version = version + 1, updated_at = now() WHERE id = ?")
                .params(status.name(), id).update();
    }

    /**
     * Riscrive i campi modificabili se la versione è ancora {@code expectedVersion} (i controlli su cosa è modificabile
     * per stato sono nel servizio); {@code false} = modificata nel frattempo (409).
     */
    public boolean update(Campaign c, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE campaign SET name = ?, description = ?, member_description = ?, icon = ?,
                          trigger_action_types = ?::text[], audience = cast(? AS jsonb), conditions = cast(? AS jsonb),
                          effects = cast(? AS jsonb), limits = cast(? AS jsonb), schedule = cast(? AS jsonb),
                          priority = ?, exclusive_group = ?, visible_in_portal = ?, labels = ?::text[],
                          version = version + 1, updated_at = now()
                        WHERE id = ? AND version = ?
                        """)
                .params(c.name(), c.description(), c.memberDescription(), c.icon(),
                        TextArrays.literal(c.triggerActionTypes()), c.audience().toString(), c.conditions().toString(),
                        c.effects().toString(), c.limits().toString(), c.schedule().toString(),
                        c.priority(), c.exclusiveGroup(), c.visibleInPortal(), TextArrays.literal(c.labels()), c.id(),
                        expectedVersion)
                .update() == 1;
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM campaign").update();
    }

    private Campaign map(ResultSet rs, int rowNum) throws SQLException {
        return new Campaign(
                rs.getString("id"), rs.getString("code"), rs.getString("name"), rs.getString("description"),
                rs.getString("member_description"), rs.getString("icon"),
                TextArrays.toList(rs.getArray("trigger_action_types")),
                json(rs.getString("audience")), json(rs.getString("conditions")), json(rs.getString("effects")),
                json(rs.getString("limits")), json(rs.getString("schedule")),
                rs.getInt("priority"), rs.getString("exclusive_group"), rs.getBoolean("visible_in_portal"),
                rs.getBoolean("system"), rs.getBoolean("requires_legal"), TextArrays.toList(rs.getArray("labels")),
                CampaignStatus.valueOf(rs.getString("status")), rs.getLong("version"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private JsonNode json(String text) {
        return text == null ? mapper.createObjectNode() : mapper.readTree(text);
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(col);
        return ts == null ? null : ts.toInstant();
    }
}
