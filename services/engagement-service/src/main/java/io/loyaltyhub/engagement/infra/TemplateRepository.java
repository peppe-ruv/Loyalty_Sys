package io.loyaltyhub.engagement.infra;

import io.loyaltyhub.engagement.domain.MessageTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Template dei messaggi (docs/servizi/engagement-service.md §2). */
@Repository
public class TemplateRepository {

    private static final String COLUMNS =
            "code, name, channel, title_tpl, body_tpl, icon, link_target, category, version, updated_at, updated_by";

    private final JdbcClient jdbc;

    public TemplateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<MessageTemplate> findAll(String category, String channel) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM message_template WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (category != null && !category.isBlank()) {
            sql.append(" AND category = ?");
            params.add(category.trim().toUpperCase());
        }
        if (channel != null && !channel.isBlank()) {
            sql.append(" AND channel = ?");
            params.add(channel.trim().toUpperCase());
        }
        sql.append(" ORDER BY code");
        return jdbc.sql(sql.toString()).params(params).query(TemplateRepository::map).list();
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
