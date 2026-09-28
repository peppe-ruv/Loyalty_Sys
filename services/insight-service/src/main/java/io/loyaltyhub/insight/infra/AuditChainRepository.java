package io.loyaltyhub.insight.infra;

import io.loyaltyhub.insight.domain.AuditAnchor;
import io.loyaltyhub.insight.domain.AuditChainHead;
import io.loyaltyhub.insight.domain.AuditChainLink;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * Lettura della catena di audit e scrittura delle ancore (F2-GRC-07, migrazione V6). Le voci si leggono così come il
 * database le conserva ({@code before::text}, {@code after::text}): è ciò che entra nella forma canonica. Gli anelli
 * li scrive solo il trigger {@code audit_entry_chain} all'inserimento; qui non si scrive mai {@code audit_entry}.
 */
@Repository
public class AuditChainRepository {

    private final JdbcClient jdbc;

    public AuditChainRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Servizi con una catena: quelli con una testa e quelli con voci (una testa mancante è essa stessa un errore). */
    public List<String> services() {
        return jdbc.sql("""
                        SELECT s.service FROM (
                          SELECT service FROM audit_chain_head
                          UNION
                          SELECT service FROM audit_entry
                        ) s
                        ORDER BY s.service COLLATE "C"
                        """)
                .query(String.class).list();
    }

    public List<AuditChainHead> heads() {
        return jdbc.sql("SELECT service, seq, entry_hash FROM audit_chain_head ORDER BY service COLLATE \"C\"")
                .query(AuditChainRepository::head).list();
    }

    public Optional<AuditChainHead> head(String service) {
        return jdbc.sql("SELECT service, seq, entry_hash FROM audit_chain_head WHERE service = ?")
                .param(service).query(AuditChainRepository::head).optional();
    }

    public boolean hasEntries(String service) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM audit_entry WHERE service = ?)")
                .param(service).query(Boolean.class).single();
    }

    /** Pagina di anelli del servizio con {@code seq > afterSeq}, in ordine di {@code seq} (paginazione a chiave). */
    public List<AuditChainLink> links(String service, long afterSeq, int limit) {
        return jdbc.sql("""
                        SELECT service, seq, prev_hash, id, event_id, at, actor_role, actor_name, entity_type,
                               entity_id, action, correlation_id, summary, before::text AS before_json,
                               after::text AS after_json, content_hash, entry_hash, redacted_at
                        FROM audit_entry
                        WHERE service = ? AND seq > ?
                        ORDER BY seq
                        LIMIT ?
                        """)
                .params(service, afterSeq, limit)
                .query(AuditChainRepository::link).list();
    }

    /** Tutte le ancore del servizio, in ordine di {@code seq}. */
    public List<AuditAnchor> anchors(String service) {
        return jdbc.sql("""
                        SELECT service, seq, entry_hash, kind, anchored_at FROM audit_anchor
                        WHERE service = ? ORDER BY seq, id
                        """)
                .param(service).query(AuditChainRepository::anchor).list();
    }

    /** Registra un'ancora sulla testa indicata; restituisce la riga scritta. */
    public AuditAnchor insertAnchor(AuditChainHead head, String kind) {
        return jdbc.sql("""
                        INSERT INTO audit_anchor (service, seq, entry_hash, kind) VALUES (?, ?, ?, ?)
                        RETURNING service, seq, entry_hash, kind, anchored_at
                        """)
                .params(head.service(), head.seq(), head.entryHash(), kind)
                .query(AuditChainRepository::anchor).single();
    }

    private static AuditChainHead head(ResultSet rs, int n) throws SQLException {
        return new AuditChainHead(rs.getString("service"), rs.getLong("seq"), rs.getString("entry_hash"));
    }

    private static AuditAnchor anchor(ResultSet rs, int n) throws SQLException {
        return new AuditAnchor(rs.getString("service"), rs.getLong("seq"), rs.getString("entry_hash"),
                rs.getString("kind"), rs.getTimestamp("anchored_at").toInstant());
    }

    private static AuditChainLink link(ResultSet rs, int n) throws SQLException {
        Timestamp at = rs.getTimestamp("at");
        Timestamp redactedAt = rs.getTimestamp("redacted_at");
        return new AuditChainLink(
                rs.getString("service"), rs.getLong("seq"), rs.getString("prev_hash"), rs.getString("id"),
                rs.getString("event_id"), at == null ? null : at.toInstant(), rs.getString("actor_role"),
                rs.getString("actor_name"), rs.getString("entity_type"), rs.getString("entity_id"),
                rs.getString("action"), rs.getString("correlation_id"), rs.getString("summary"),
                rs.getString("before_json"), rs.getString("after_json"), rs.getString("content_hash"),
                rs.getString("entry_hash"), redactedAt == null ? null : redactedAt.toInstant());
    }
}
