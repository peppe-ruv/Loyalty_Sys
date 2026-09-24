package io.loyaltyhub.engagement.infra;

import io.loyaltyhub.engagement.domain.InboxMessage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Inbox e registro dei messaggi (docs/servizi/engagement-service.md §2). */
@Repository
public class InboxRepository {

    private static final String COLUMNS = """
            id, member_id, template_code, channel, title, body, icon, link_target, category, source_event_id, source_type,
            correlation_id, created_at, read_at""";

    /** Filtri del registro messaggi (BO-19, Scheda 360°); {@code null} = nessun filtro. */
    public record Filter(String memberId, String category, String channel, String templateCode) {
    }

    private final JdbcClient jdbc;

    public InboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserisce il messaggio se non esiste già la terna (membro, evento sorgente, template) (docs/03 §9).
     * @return {@code true} se inserito ora, {@code false} se era un duplicato
     */
    public boolean insertIfAbsent(InboxMessage m) {
        return jdbc.sql("""
                        INSERT INTO inbox_message (id, member_id, template_code, channel, title, body, icon, link_target, category,
                          source_event_id, source_type, correlation_id, created_at, read_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (member_id, source_event_id, template_code) DO NOTHING
                        """)
                .params(m.id(), m.memberId(), m.templateCode(), m.channel(), m.title(), m.body(), m.icon(), m.linkTarget(),
                        m.category(), m.sourceEventId(), m.sourceType(), m.correlationId(), ts(m.createdAt()), ts(m.readAt()))
                .update() == 1;
    }

    public Optional<InboxMessage> find(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM inbox_message WHERE id = ?").param(id)
                .query(InboxRepository::map).optional();
    }

    public List<InboxMessage> search(Filter f, int page, int size) {
        List<Object> params = new ArrayList<>();
        String where = where(f, params);
        params.add(size);
        params.add((long) page * size);
        return jdbc.sql("SELECT " + COLUMNS + " FROM inbox_message" + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?")
                .params(params).query(InboxRepository::map).list();
    }

    public long count(Filter f) {
        List<Object> params = new ArrayList<>();
        String where = where(f, params);
        return jdbc.sql("SELECT count(*) FROM inbox_message" + where).params(params).query(Long.class).single();
    }

    /** Non letti del canale {@code INAPP} (la campanella del portale). */
    public long unreadInApp(String memberId) {
        return jdbc.sql("SELECT count(*) FROM inbox_message WHERE member_id = ? AND channel = 'INAPP' AND read_at IS NULL")
                .param(memberId).query(Long.class).single();
    }

    /** Segna letto un messaggio del membro (idempotente: la prima lettura resta). */
    public boolean markRead(String id, String memberId, Instant at) {
        jdbc.sql("UPDATE inbox_message SET read_at = ? WHERE id = ? AND member_id = ? AND read_at IS NULL")
                .params(ts(at), id, memberId).update();
        return jdbc.sql("SELECT count(*) FROM inbox_message WHERE id = ? AND member_id = ?").params(id, memberId)
                .query(Long.class).single() > 0;
    }

    /** Segna letti tutti i messaggi {@code INAPP} del membro; ritorna quanti erano non letti. */
    public int markAllRead(String memberId, Instant at) {
        return jdbc.sql("UPDATE inbox_message SET read_at = ? WHERE member_id = ? AND channel = 'INAPP' AND read_at IS NULL")
                .params(ts(at), memberId).update();
    }

    public int deleteOlderThan(Instant before) {
        return jdbc.sql("DELETE FROM inbox_message WHERE created_at < ?").param(ts(before)).update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM inbox_message").update();
    }

    private static String where(Filter f, List<Object> params) {
        StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        if (f.memberId() != null && !f.memberId().isBlank()) {
            sql.append(" AND member_id = ?");
            params.add(f.memberId().trim());
        }
        if (f.category() != null && !f.category().isBlank()) {
            sql.append(" AND category = ?");
            params.add(f.category().trim().toUpperCase());
        }
        if (f.channel() != null && !f.channel().isBlank()) {
            sql.append(" AND channel = ?");
            params.add(f.channel().trim().toUpperCase());
        }
        if (f.templateCode() != null && !f.templateCode().isBlank()) {
            sql.append(" AND template_code = ?");
            params.add(f.templateCode().trim().toUpperCase());
        }
        return sql.toString();
    }

    private static Timestamp ts(Instant at) {
        return at == null ? null : Timestamp.from(at);
    }

    private static InboxMessage map(ResultSet rs, int n) throws SQLException {
        Timestamp read = rs.getTimestamp("read_at");
        return new InboxMessage(rs.getString("id"), rs.getString("member_id"), rs.getString("template_code"),
                rs.getString("channel"), rs.getString("title"), rs.getString("body"), rs.getString("icon"),
                rs.getString("link_target"), rs.getString("category"), rs.getString("source_event_id"),
                rs.getString("source_type"), rs.getString("correlation_id"), rs.getTimestamp("created_at").toInstant(),
                read == null ? null : read.toInstant());
    }
}
