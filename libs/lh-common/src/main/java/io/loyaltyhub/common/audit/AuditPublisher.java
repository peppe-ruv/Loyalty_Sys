package io.loyaltyhub.common.audit;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.event.LhSource;
import io.loyaltyhub.common.kafka.LoyaltyHubProperties;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.common.web.ActorHolder;

/**
 * Pubblica una voce di audit su {@code lh.audit.v1} tramite outbox (docs/05 §6, docs/06 §1).
 * Chiave = {@code entityType:entityId}; {@code lhactor} obbligatorio (attore corrente o {@code system}).
 * Da chiamare dentro la stessa transazione della scrittura che si sta auditando.
 */
public class AuditPublisher {

    private final OutboxWriter outbox;
    private final LhEventFactory events;
    private final LoyaltyHubProperties props;

    public AuditPublisher(OutboxWriter outbox, LhEventFactory events, LoyaltyHubProperties props) {
        this.outbox = outbox;
        this.events = events;
        this.props = props;
    }

    /** Audit con l'attore corrente della richiesta ({@link ActorHolder}). */
    public void record(String entityType, String entityId, AuditEntry.Action action,
                       String summary, Object before, Object after) {
        record(entityType, entityId, action, summary, before, after, ActorHolder.get().asActorString());
    }

    /** Audit di un job (attore {@code system}). */
    public void recordJob(String entityType, String entityId, String summary, Object before, Object after) {
        record(entityType, entityId, AuditEntry.Action.JOB, summary, before, after, "system");
    }

    private void record(String entityType, String entityId, AuditEntry.Action action,
                        String summary, Object before, Object after, String actor) {
        AuditEntry entry = new AuditEntry(props.getService(), entityType, entityId, action, summary, before, after);
        String subject = entityType + ":" + entityId;
        LhEvent<AuditEntry> event = events.newRoot(
                LhEventTypes.Audit.ENTRY, subject, entry, LhSource.service(props.getService()), actor);
        outbox.write(props.getTopics().getAudit(), subject, event);
    }
}
