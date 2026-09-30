package io.loyaltyhub.engagement.infra;

import io.loyaltyhub.common.sql.SqlColumn;
import io.loyaltyhub.common.sql.SqlOrder;
import io.loyaltyhub.common.sql.SqlWhere;
import io.loyaltyhub.engagement.domain.MessageTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Template dei messaggi (docs/servizi/engagement-service.md §2). */
@Repository
public class TemplateRepository {

    private static final String COLUMNS =
            "code, name, channel, title_tpl, body_tpl, icon, link_target, category, version, updated_at, updated_by";

    /** Colonne ammesse nei filtri e nell'ordinamento di {@link #findAll} (regola 19, ADR-042). */
    enum TemplateColumn implements SqlColumn {
        CODE("code"), CATEGORY("category"), CHANNEL("channel");

        private final String sql;

        TemplateColumn(String sql) {
            this.sql = sql;
        }

        @Override
        public String sql() {
            return sql;
        }
    }

    /** Testo SQL costante dell'elenco: vi si accodano solo {@link SqlWhere#sql()} e {@link #LIST_ORDER}. */
    private static final String LIST_SELECT = "SELECT " + COLUMNS + " FROM message_template";

    /** Ordinamento costante dell'elenco, per codice come prima del builder. */
    private static final String LIST_ORDER = SqlOrder.asc(TemplateColumn.CODE).sql();

    private final JdbcClient jdbc;

    public TemplateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<MessageTemplate> findAll(String category, String channel) {
        SqlWhere where = filters(category, channel);
        return where.bind(jdbc.sql(LIST_SELECT + where.sql() + LIST_ORDER)).query(TemplateRepository::map).list();
    }

    /**
     * Filtri facoltativi dell'elenco (regola 19, ADR-042): categoria e canale per uguaglianza, in maiuscolo; ogni
     * valore diventa un parametro legato, mai testo SQL.
     */
    static SqlWhere filters(String category, String channel) {
        return new SqlWhere()
                .when(category != null && !category.isBlank(),
                        w -> w.eq(TemplateColumn.CATEGORY, category.trim().toUpperCase()))
                .when(channel != null && !channel.isBlank(),
                        w -> w.eq(TemplateColumn.CHANNEL, channel.trim().toUpperCase()));
    }

    /**
     * Template per codice esatto (chiave esterna di {@code notification_rule.template_code}, senza la normalizzazione
     * di {@link #find}): un parametro legato per codice tramite {@link SqlWhere#in} (regola 19, ADR-042); insieme
     * vuoto o assente = lista vuota, senza interrogare il database.
     */
    public List<MessageTemplate> findByCodes(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        SqlWhere where = new SqlWhere().in(TemplateColumn.CODE, codes);
        return where.bind(jdbc.sql(LIST_SELECT + where.sql())).query(TemplateRepository::map).list();
    }

    public Optional<MessageTemplate> find(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM message_template WHERE code = ?").param(code.trim().toUpperCase())
                .query(TemplateRepository::map).optional();
    }

    public void insert(MessageTemplate t, String actor) {
        jdbc.sql("""
                        INSERT INTO message_template (code, name, channel, title_tpl, body_tpl, icon, link_target, category,
                          created_by, updated_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(t.code(), t.name(), t.channel(), t.titleTpl(), t.bodyTpl(), t.icon(), t.linkTarget(), t.category(),
                        actor, actor)
                .update();
    }

    /** Aggiorna se la versione è ancora {@code expectedVersion}; {@code false} = modificato nel frattempo (409). */
    public boolean update(MessageTemplate t, long expectedVersion, String actor) {
        return jdbc.sql("""
                        UPDATE message_template SET name = ?, channel = ?, title_tpl = ?, body_tpl = ?, icon = ?, link_target = ?,
                          category = ?, version = version + 1, updated_at = now(), updated_by = ?
                        WHERE code = ? AND version = ?
                        """)
                .params(t.name(), t.channel(), t.titleTpl(), t.bodyTpl(), t.icon(), t.linkTarget(), t.category(), actor,
                        t.code(), expectedVersion)
                .update() == 1;
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM message_template").update();
    }

    private static MessageTemplate map(ResultSet rs, int n) throws SQLException {
        return new MessageTemplate(rs.getString("code"), rs.getString("name"), rs.getString("channel"),
                rs.getString("title_tpl"), rs.getString("body_tpl"), rs.getString("icon"), rs.getString("link_target"),
                rs.getString("category"), rs.getLong("version"), rs.getTimestamp("updated_at").toInstant(),
                rs.getString("updated_by"));
    }
}
