package io.loyaltyhub.engagement.infra;

import io.loyaltyhub.common.sql.SqlColumn;
import io.loyaltyhub.common.sql.SqlOrder;
import io.loyaltyhub.common.sql.SqlWhere;
import io.loyaltyhub.engagement.domain.InboxMessage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Inbox e registro dei messaggi (docs/servizi/engagement-service.md §2). */
@Repository
public class InboxRepository {

    private static final String COLUMNS = """
            id, member_id, template_code, channel, title, body, icon, link_target, category, source_event_id, source_type,
            correlation_id, created_at, read_at""";

    /** Colonne ammesse nei filtri e nell'ordinamento di {@link #search}/{@link #count} (regola 19, ADR-042). */
    enum InboxColumn implements SqlColumn {
        ID("id"), MEMBER_ID("member_id"), CATEGORY("category"), CHANNEL("channel"), TEMPLATE_CODE("template_code"),
        CREATED_AT("created_at");

        private final String sql;

        InboxColumn(String sql) {
            this.sql = sql;
        }

        @Override
        public String sql() {
            return sql;
        }
    }

    /** Testo SQL costante del registro: vi si accodano solo {@link SqlWhere#sql()} e {@link #SEARCH_PAGE}. */
    private static final String SEARCH_SELECT = "SELECT " + COLUMNS + " FROM inbox_message";

    /** Come {@link #SEARCH_SELECT}, per il totale: vi si accoda solo {@link SqlWhere#sql()}. */
    private static final String COUNT_SELECT = "SELECT count(*) FROM inbox_message";

    /** Ordine costante: più recenti prima, spareggio su id; pagina legata ({@code :limit}, {@code :offset}). */
    private static final String SEARCH_PAGE = SqlOrder.desc(InboxColumn.CREATED_AT)
            .by(InboxColumn.ID, SqlOrder.Direction.DESC).sql() + " LIMIT :limit OFFSET :offset";

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
        SqlWhere where = filters(f);
        return where.bind(jdbc.sql(SEARCH_SELECT + where.sql() + SEARCH_PAGE))
                .param("limit", size)
                .param("offset", (long) page * size)
                .query(InboxRepository::map).list();
    }

    public long count(Filter f) {
        SqlWhere where = filters(f);
        return where.bind(jdbc.sql(COUNT_SELECT + where.sql())).query(Long.class).single();
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

    /**
     * Filtri facoltativi del registro (regola 19, ADR-042): membro così com'è, categoria, canale e template in
     * maiuscolo, per uguaglianza; ogni valore diventa un parametro legato, mai testo SQL.
     */
    static SqlWhere filters(Filter f) {
        return new SqlWhere()
                .when(present(f.memberId()), w -> w.eq(InboxColumn.MEMBER_ID, f.memberId().trim()))
                .when(present(f.category()), w -> w.eq(InboxColumn.CATEGORY, f.category().trim().toUpperCase()))
                .when(present(f.channel()), w -> w.eq(InboxColumn.CHANNEL, f.channel().trim().toUpperCase()))
                .when(present(f.templateCode()),
                        w -> w.eq(InboxColumn.TEMPLATE_CODE, f.templateCode().trim().toUpperCase()));
    }

    private static boolean present(String v) {
        return v != null && !v.isBlank();
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
