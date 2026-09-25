package io.loyaltyhub.common.audit;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.event.LhSource;
import io.loyaltyhub.common.kafka.LoyaltyHubProperties;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.common.web.ActorHolder;

import java.util.Optional;

/**
 * Pubblica una voce di audit su {@code lh.audit.v1} tramite outbox (docs/05 §6, docs/06 §1).
 * Chiave = {@code entityType:entityId}; {@code lhactor} obbligatorio (attore corrente o {@code system}).
 * Da chiamare dentro la stessa transazione della scrittura che si sta auditando.
 * <p>
 * {@code data.service} (e il {@code source}) è il servizio <em>logico</em> che scrive (docs/05 §6: es. {@code wallet}),
 * ricavato dal package del chiamante ({@code io.loyaltyhub.<servizio>.…}): nel deployable consolidato (ADR-023) un solo
 * publisher serve tutti i servizi e {@code loyaltyhub.service} vale {@code hub}, che BO-22 non saprebbe filtrare.
 * Chiamanti fuori da un servizio (es. il reset comune di {@code lh-common}) usano {@code loyaltyhub.service}.
 */
public class AuditPublisher {

    private static final String BASE_PACKAGE = "io.loyaltyhub.";
    private static final String COMMON_PACKAGE = "io.loyaltyhub.common";
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

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
        String service = callerService().orElse(props.getService());
        AuditEntry entry = new AuditEntry(service, entityType, entityId, action, summary, before, after);
        String subject = entityType + ":" + entityId;
        LhEvent<AuditEntry> event = events.newRoot(
                LhEventTypes.Audit.ENTRY, subject, entry, LhSource.service(service), actor);
        outbox.write(props.getTopics().getAudit(), subject, event);
    }

    /**
     * Servizio logico del chiamante diretto fuori da {@code lh-common}: il segmento dopo {@code io.loyaltyhub.} del
     * package del primo frame che non appartiene a {@code io.loyaltyhub.common} ({@code io.loyaltyhub.wallet.X} →
     * {@code wallet}). Vuoto se quel frame non è di un servizio (codice comune chiamato dal framework, es. il reset
     * demo, o il deployable {@code hub}): un filtro di un servizio più in basso nello stack non conta.
     */
    static Optional<String> callerService() {
        return WALKER.walk(frames -> frames
                .map(f -> f.getDeclaringClass().getPackageName())
                .filter(p -> !p.equals(COMMON_PACKAGE) && !p.startsWith(COMMON_PACKAGE + "."))
                .findFirst()
                .filter(p -> p.startsWith(BASE_PACKAGE))
                .map(p -> {
                    String rest = p.substring(BASE_PACKAGE.length());
                    int dot = rest.indexOf('.');
                    return dot < 0 ? rest : rest.substring(0, dot);
                })
                .filter(s -> !s.isEmpty() && !"hub".equals(s)));
    }
}
