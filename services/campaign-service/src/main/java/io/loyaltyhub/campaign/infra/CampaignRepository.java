package io.loyaltyhub.campaign.infra;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.campaign.domain.Campaign;
import io.loyaltyhub.campaign.domain.CampaignStatus;
import io.loyaltyhub.common.sql.SqlColumn;
import io.loyaltyhub.common.sql.SqlOrder;
import io.loyaltyhub.common.sql.SqlWhere;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistenza delle campagne (docs/servizi/campaign-service.md §2). I campi jsonb restano {@link JsonNode}. */
@Repository
public class CampaignRepository {

    /** Colonne ammesse nei filtri e nell'ordinamento di {@link #search} (regola 19, ADR-042). */
    enum CampaignColumn implements SqlColumn {
        STATUS("status"), CODE("code"), NAME("name"), PRIORITY("priority");

        private final String sql;

        CampaignColumn(String sql) {
            this.sql = sql;
        }

        @Override
        public String sql() {
            return sql;
        }
    }

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

    /**
     * Testo SQL costante dell'elenco (regola 19, docs/18 §3.10 punto 4): il filtro per tipo di azione è sempre presente
     * e sempre legato, e {@code :actionType} nullo lo disattiva. Vi si accodano solo {@link SqlWhere#andSql()} e
     * l'ordinamento di {@link #searchOrder()}.
     */
    private static final String SEARCH = "SELECT " + COLS + """
            FROM campaign
            WHERE (CAST(:actionType AS text) IS NULL OR CAST(:actionType AS text) = ANY(trigger_action_types))
            """;

    /**
     * Elenco filtrato ({@code GET /v1/campaigns}, docs §3). Filtri facoltativi, ignorati se assenti o vuoti: stato per
     * uguaglianza (ripulito e in maiuscolo), tipo di azione tra i {@code trigger_action_types} (ripulito), {@code q}
     * come testo letterale contenuto nel codice o nel nome, senza distinzione tra maiuscole e minuscole ({@code %},
     * {@code _} e {@code \} non sono caratteri jolly). Ordine: priorità decrescente, poi codice.
     */
    public List<Campaign> search(String status, String actionType, String q) {
        SqlWhere where = new SqlWhere()
                .when(present(status), w -> w.eq(CampaignColumn.STATUS, status.trim().toUpperCase()))
                .when(present(q), w -> w.anyOf(any -> any
                        .ilike(CampaignColumn.CODE, q.trim())
                        .ilike(CampaignColumn.NAME, q.trim())));
        return where.bind(jdbc.sql(SEARCH + where.andSql() + searchOrder().sql()))
                .param("actionType", present(actionType) ? actionType.trim() : null, Types.VARCHAR)
                .query(this::map).list();
    }

    /** Ordinamento dell'elenco, nuovo a ogni chiamata perché {@link SqlOrder} è mutabile. */
    private static SqlOrder searchOrder() {
        return SqlOrder.desc(CampaignColumn.PRIORITY).by(CampaignColumn.CODE, SqlOrder.Direction.ASC);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
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
