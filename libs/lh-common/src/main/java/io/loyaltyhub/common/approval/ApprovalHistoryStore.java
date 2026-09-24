package io.loyaltyhub.common.approval;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Storico delle transizioni degli oggetti governati (tabella {@code approval_history} nello schema di ogni servizio
 * proprietario, docs/06 §1, §7): chi, quando, da/verso, azione, commento. Nell'hub consolidato la tabella risolta dal
 * {@code search_path} è condivisa: le righe si distinguono per {@code entity_type}.
 */
public class ApprovalHistoryStore {

    private final JdbcClient jdbc;

    public ApprovalHistoryStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String entityType, String entityId, ApprovalStatus from, ApprovalStatus to, ApprovalAction action,
                       String actor, String comment, Instant at) {
        jdbc.sql("""
                        INSERT INTO approval_history (id, entity_type, entity_id, from_status, to_status, action, actor, comment, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(UUID.randomUUID(), entityType, entityId, from == null ? null : from.name(), to.name(),
                        action.name(), actor, comment == null || comment.isBlank() ? null : comment.trim(),
                        Timestamp.from(at))
                .update();
    }

    public List<ApprovalHistory> list(String entityType, String entityId) {
        return jdbc.sql("""
                        SELECT id, entity_type, entity_id, from_status, to_status, action, actor, comment, created_at
                        FROM approval_history WHERE entity_type = ? AND entity_id = ? ORDER BY created_at DESC, id
                        """)
                .params(entityType, entityId)
                .query((rs, n) -> new ApprovalHistory(
                        rs.getObject("id", UUID.class), rs.getString("entity_type"), rs.getString("entity_id"),
                        rs.getString("from_status") == null ? null : ApprovalStatus.valueOf(rs.getString("from_status")),
                        ApprovalStatus.valueOf(rs.getString("to_status")), ApprovalAction.valueOf(rs.getString("action")),
                        rs.getString("actor"), rs.getString("comment"), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    /** Ultimo invio in revisione dell'oggetto (chi e quando). */
    public Optional<ApprovalHistory> lastOf(String entityType, String entityId, ApprovalAction action) {
        return list(entityType, entityId).stream().filter(h -> h.action() == action).findFirst();
    }

    /** Oggetti del tipo inviati in revisione da {@code actor} (id distinti, dal più recente). */
    public List<String> submittedBy(String entityType, String actor) {
        return jdbc.sql("""
                        SELECT entity_id FROM approval_history
                        WHERE entity_type = ? AND action = 'SUBMIT' AND actor = ?
                        GROUP BY entity_id ORDER BY max(created_at) DESC LIMIT 50
                        """)
                .params(entityType, actor)
                .query(String.class)
                .list();
    }

    public void deleteAll(String entityType) {
        jdbc.sql("DELETE FROM approval_history WHERE entity_type = ?").param(entityType).update();
    }
}
