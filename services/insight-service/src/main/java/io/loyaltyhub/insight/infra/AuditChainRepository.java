package io.loyaltyhub.insight.infra;

import io.loyaltyhub.insight.domain.AuditAnchor;
import io.loyaltyhub.insight.domain.AuditChainHead;
import io.loyaltyhub.insight.domain.AuditChainLink;
import io.loyaltyhub.insight.domain.RedactionEvidence;
import io.loyaltyhub.insight.domain.RedactionLookup;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Lettura della catena di audit e scrittura delle ancore (F2-GRC-07, migrazione V6). Le voci si leggono così come il
 * database le conserva ({@code before::text}, {@code after::text}): è ciò che entra nella forma canonica. Gli anelli
 * li scrive solo il trigger {@code audit_entry_chain} all'inserimento; qui non si scrive mai {@code audit_entry}.
 */
@Repository
public class AuditChainRepository {

    /** Servizio della catena che raccoglie le prove REDACT delle anonimizzazioni (V6). */
    public static final String REDACTION_SERVICE = "insight";

    private static final String LINK_COLUMNS = """
            service, seq, prev_hash, id, event_id, at, actor_role, actor_name, entity_type, entity_id, action,
            correlation_id, summary, before::text AS before_json, after::text AS after_json, content_hash,
            entry_hash, redacted_at""";

    private final JdbcClient jdbc;

    public AuditChainRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Servizi con una catena: quelli con una testa, con voci o con ancore. Un servizio di cui restano solo le ancore
     * (voci e testa cancellate) compare lo stesso, e la verifica lo segnala.
     */
    public List<String> services() {
        return jdbc.sql("""
                        SELECT s.service FROM (
                          SELECT service FROM audit_chain_head
                          UNION
                          SELECT service FROM audit_entry
                          UNION
                          SELECT service FROM audit_anchor
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

    /** Il servizio ha lasciato traccia: una testa, una voce o un'ancora. */
    public boolean known(String service) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM audit_chain_head WHERE service = ?)
                            OR EXISTS (SELECT 1 FROM audit_entry WHERE service = ?)
                            OR EXISTS (SELECT 1 FROM audit_anchor WHERE service = ?)
                        """)
                .params(service, service, service).query(Boolean.class).single();
    }

    /** Pagina di anelli del servizio con {@code seq > afterSeq}, in ordine di {@code seq} (paginazione a chiave). */
    public List<AuditChainLink> links(String service, long afterSeq, int limit) {
        return jdbc.sql("SELECT " + LINK_COLUMNS + """

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

    /**
     * Registra un'ancora sulla testa indicata. Vuoto se la stessa ancora (servizio, seq, tipo) c'era già, per esempio
     * scritta da un'altra replica del job.
     */
    public Optional<AuditAnchor> insertAnchor(AuditChainHead head, String kind) {
        return jdbc.sql("""
                        INSERT INTO audit_anchor (service, seq, entry_hash, kind) VALUES (?, ?, ?, ?)
                        ON CONFLICT (service, seq, kind) DO NOTHING
                        RETURNING service, seq, entry_hash, kind, anchored_at
                        """)
                .params(head.service(), head.seq(), head.entryHash(), kind)
                .query(AuditChainRepository::anchor).optional();
    }

    /** Le prove REDACT della catena {@code insight}, lette dalla stessa transazione della verifica. */
    public RedactionLookup redactions() {
        return new RedactionLookup() {
            @Override
            public Optional<RedactionEvidence> latest(String entryId) {
                return jdbc.sql("SELECT " + LINK_COLUMNS + """
                                , after ->> 'service' AS target_service, after ->> 'seq' AS target_seq,
                                  after ->> 'contentHash' AS target_content_hash, after ->> 'memberId' AS member_id
                                FROM audit_entry
                                WHERE entity_type = 'AUDIT_ENTRY' AND entity_id = ? AND service = ? AND action = 'REDACT'
                                ORDER BY seq DESC
                                LIMIT 1
                                """)
                        .params(entryId, REDACTION_SERVICE)
                        .query((rs, n) -> new RedactionEvidence(link(rs, n), rs.getString("target_service"),
                                longOrNull(rs.getString("target_seq")), rs.getString("target_content_hash"),
                                rs.getString("member_id")))
                        .optional();
            }

            @Override
            public Optional<Instant> retainedFrom() {
                Optional<Instant> first = jdbc.sql("SELECT at FROM audit_entry WHERE service = ? ORDER BY seq LIMIT 1")
                        .param(REDACTION_SERVICE).query(Timestamp.class).optional().map(Timestamp::toInstant);
                if (first.isPresent()) {
                    return first;
                }
                return head(REDACTION_SERVICE).filter(h -> h.seq() > 0).map(h -> Instant.MAX);
            }
        };
    }

    private static Long longOrNull(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static AuditChainHead head(ResultSet rs, int n) throws SQLException {
        return new AuditChainHead(rs.getString("service"), rs.getLong("seq"), rs.getString("entry_hash"));
    }

    static AuditAnchor anchor(ResultSet rs, int n) throws SQLException {
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
