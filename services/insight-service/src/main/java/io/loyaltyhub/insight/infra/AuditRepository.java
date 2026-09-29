package io.loyaltyhub.insight.infra;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.common.sql.SqlColumn;
import io.loyaltyhub.common.sql.SqlOrder;
import io.loyaltyhub.common.sql.SqlWhere;
import io.loyaltyhub.insight.domain.AuditAnchor;
import io.loyaltyhub.insight.domain.AuditRecord;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Voci di audit (docs/servizi/insight-service.md §2, §3). Inserimento idempotente su {@code event_id}
 * ({@code ON CONFLICT DO NOTHING}); ricerca con filtri e dettaglio per {@code id}. Retention 180 giorni (§5).
 * La tabella è in sola inserzione e ogni voce entra nella catena di hash del proprio servizio (V6, F2-GRC-07): la
 * catena la calcola il trigger all'inserimento, retention e reset passano dalle funzioni controllate del database
 * ({@code audit_purge_before}, {@code audit_reset}).
 */
@Repository
public class AuditRepository {

    /** Colonne ammesse nei filtri e nell'ordinamento di {@link #search}/{@link #count} (regola 19, ADR-042). */
    enum AuditColumn implements SqlColumn {
        ACTOR_NAME("actor_name"), ACTOR_ROLE("actor_role"), SERVICE("service"), ENTITY_TYPE("entity_type"),
        ENTITY_ID("entity_id"), ACTION("action"), AT("at");

        private final String sql;

        AuditColumn(String sql) {
            this.sql = sql;
        }

        @Override
        public String sql() {
            return sql;
        }
    }

    /**
     * Testo SQL costante dell'elenco (regola 19, docs/18 §3.10 punto 4): vi si accodano solo {@link SqlWhere#sql()},
     * l'ordinamento costante {@link #SEARCH_ORDER} e la pagina legata {@link #SEARCH_PAGE}. Solo lettura: la tabella
     * resta in sola inserzione (ADR-043).
     */
    static final String SEARCH_SELECT = "SELECT * FROM audit_entry";

    /** Come {@link #SEARCH_SELECT}, per il totale: vi si accoda solo {@link SqlWhere#sql()}. */
    static final String COUNT_SELECT = "SELECT count(*) FROM audit_entry";

    /** Dalla voce più recente, come prima del builder. */
    static final String SEARCH_ORDER = SqlOrder.desc(AuditColumn.AT).sql();

    static final String SEARCH_PAGE = " LIMIT :limit OFFSET :offset";

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
        SqlWhere where = filters(actor, role, service, entityType, entityId, action, from, to);
        return where.bind(jdbc.sql(SEARCH_SELECT + where.sql() + SEARCH_ORDER + SEARCH_PAGE))
                .param("limit", limit)
                .param("offset", offset)
                .query(this::map).list();
    }

    public long count(String actor, String role, String service, String entityType,
                      String entityId, String action, Instant from, Instant to) {
        SqlWhere where = filters(actor, role, service, entityType, entityId, action, from, to);
        return where.bind(jdbc.sql(COUNT_SELECT + where.sql())).query(Long.class).single();
    }

    /**
     * Filtri facoltativi per uguaglianza, ignorati se assenti o vuoti. I valori passano così come arrivano, senza
     * togliere spazi, come prima del builder; l'intervallo {@code [from, to]} è su {@code at}, estremi inclusi.
     */
    static SqlWhere filters(String actor, String role, String service, String entityType, String entityId,
                            String action, Instant from, Instant to) {
        return new SqlWhere()
                .eqIfPresent(AuditColumn.ACTOR_NAME, actor)
                .eqIfPresent(AuditColumn.ACTOR_ROLE, role)
                .eqIfPresent(AuditColumn.SERVICE, service)
                .eqIfPresent(AuditColumn.ENTITY_TYPE, entityType)
                .eqIfPresent(AuditColumn.ENTITY_ID, entityId)
                .eqIfPresent(AuditColumn.ACTION, action)
                .when(from != null, w -> w.gte(AuditColumn.AT, Timestamp.from(from)))
                .when(to != null, w -> w.lte(AuditColumn.AT, Timestamp.from(to)));
    }

    /** Esito della retention dell'audit: voci cancellate e ancore PURGE lasciate (una per catena accorciata). */
    public record Purge(int deleted, List<AuditAnchor> anchors) {
    }

    /**
     * Una catena la cui retention è ferma: voci scadute che seguono una voce ancora nella finestra
     * ({@code blockingSeq}, datata {@code blockingAt}) e che quindi restano finché non scade anche quella (Q-402).
     */
    public record RetentionStall(String service, long blockingSeq, Instant blockingAt, long expiredKept) {
    }

    /**
     * Retention (§5): cancella, per ogni catena, le voci più vecchie di {@code days} giorni che ne formano la parte
     * iniziale (funzione {@code audit_purge_before}, V6, che non scende mai sotto {@code audit_min_retention_days()}).
     */
    public Purge deleteOlderThan(int days) {
        return purge(jdbc.sql("SELECT * FROM audit_purge_before(now() - make_interval(days => ?))").param(days));
    }

    /** Come {@link #deleteOlderThan(int)} con una soglia esplicita (sempre limitata dall'età minima). */
    public Purge deleteBefore(Instant threshold) {
        return purge(jdbc.sql("SELECT * FROM audit_purge_before(?)").param(Timestamp.from(threshold)));
    }

    /** Una riga di {@code audit_purge_before}: la catena accorciata, la sua ancora PURGE e le voci cancellate. */
    private record PurgedChain(AuditAnchor anchor, int deleted) {
    }

    private Purge purge(JdbcClient.StatementSpec statement) {
        List<PurgedChain> chains = statement.query((rs, n) -> new PurgedChain(
                new AuditAnchor(rs.getString("anchor_service"), rs.getLong("anchor_seq"), rs.getString("anchor_hash"),
                        AuditAnchor.PURGE, rs.getTimestamp("anchor_at").toInstant()),
                rs.getInt("purged_rows"))).list();
        return new Purge(chains.stream().mapToInt(PurgedChain::deleted).sum(),
                chains.stream().map(PurgedChain::anchor).toList());
    }

    /**
     * Catene con voci più vecchie di {@code days} giorni che la retention non può cancellare perché seguono una voce
     * più recente (Q-402). Stessa soglia di {@link #deleteOlderThan(int)}.
     */
    public List<RetentionStall> retentionStalls(int days) {
        return jdbc.sql("""
                        WITH t AS (SELECT least(now() - make_interval(days => ?),
                                                now() - make_interval(days => audit_min_retention_days())) AS threshold),
                             blocking AS (
                               SELECT DISTINCT ON (e.service) e.service, e.seq, e.at
                               FROM audit_entry e, t WHERE e.at >= t.threshold
                               ORDER BY e.service, e.seq)
                        SELECT b.service, b.seq, b.at, count(o.id) AS kept
                        FROM blocking b
                        JOIN audit_entry o ON o.service = b.service AND o.seq > b.seq
                        CROSS JOIN t
                        WHERE o.at < t.threshold
                        GROUP BY b.service, b.seq, b.at
                        ORDER BY b.service
                        """)
                .param(days)
                .query((rs, n) -> new RetentionStall(rs.getString("service"), rs.getLong("seq"),
                        rs.getTimestamp("at").toInstant(), rs.getLong("kept")))
                .list();
    }

    /** Reset della demo (§6): voci, teste e ancore; le catene ripartono dalla genesi (funzione {@code audit_reset}). */
    public void deleteAll() {
        jdbc.sql("SELECT audit_reset()").query(Integer.class).single();
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
