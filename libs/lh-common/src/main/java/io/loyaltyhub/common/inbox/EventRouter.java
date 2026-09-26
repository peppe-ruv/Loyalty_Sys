package io.loyaltyhub.common.inbox;

import tools.jackson.databind.JsonNode;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.metrics.LhMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Instrada un evento all'{@link EventHandler} registrato per il suo {@code type} (docs/06 §5). Un type che termina
 * con {@code .*} (es. {@code io.loyaltyhub.action.*}) registra l'handler per tutta la famiglia: vale quando nessun
 * handler è registrato per il type esatto (servono, per esempio, gli obiettivi che osservano qualunque azione).
 * Un {@code type} non registrato viene <em>ignorato senza errore</em> e senza scrivere {@code processed_event}
 * (docs/05 §1). Il consumo passa dall'{@link IdempotentHandler}: doppio invio ⇒ stesso stato.
 */
public class EventRouter {

    private static final Logger log = LoggerFactory.getLogger(EventRouter.class);

    private final String consumer;
    private final Map<String, EventHandler> byType = new HashMap<>();
    private final Map<String, EventHandler> byPrefix = new java.util.LinkedHashMap<>();
    private final IdempotentHandler idempotent;
    private final LhMetrics metrics;

    public EventRouter(String consumer, List<EventHandler> handlers, IdempotentHandler idempotent, LhMetrics metrics) {
        this.consumer = consumer;
        this.idempotent = idempotent;
        this.metrics = metrics;
        for (EventHandler handler : handlers) {
            for (String type : handler.handledTypes()) {
                if (type.endsWith(".*")) {
                    if (byPrefix.put(type.substring(0, type.length() - 1), handler) != null) {
                        throw new IllegalStateException("Due handler per la stessa famiglia: " + type);
                    }
                    continue;
                }
                EventHandler previous = byType.put(type, handler);
                if (previous != null) {
                    throw new IllegalStateException("Due handler per lo stesso type: " + type);
                }
            }
        }
    }

    /**
     * Instrada l'evento. Ritorna {@code true} se un handler lo ha elaborato ora, {@code false} se
     * ignorato (type non registrato) o duplicato.
     */
    public boolean route(LhEvent<JsonNode> event) {
        EventHandler handler = handlerFor(event.type());
        if (handler == null) {
            log.trace("Type non gestito da {}, ignorato: {}", consumer, event.type());
            return false;
        }
        metrics.eventConsumed(event.type());
        long start = System.nanoTime();
        // Contesto dei log durante l'elaborazione (docs/06 §8, RNF-10): eventId, eventType, correlationId, memberId.
        put(MDC_EVENT_ID, event.id());
        put(MDC_EVENT_TYPE, event.type());
        put(MDC_CORRELATION_ID, event.lhcorrelationid());
        put(MDC_MEMBER_ID, event.memberId());
        try {
            return idempotent.handle(consumer, event, handler::handle);
        } finally {
            metrics.handlerTime(event.type(), System.nanoTime() - start);
            org.slf4j.MDC.remove(MDC_EVENT_ID);
            org.slf4j.MDC.remove(MDC_EVENT_TYPE);
            org.slf4j.MDC.remove(MDC_CORRELATION_ID);
            org.slf4j.MDC.remove(MDC_MEMBER_ID);
        }
    }

    static final String MDC_EVENT_ID = "eventId";
    static final String MDC_EVENT_TYPE = "eventType";
    static final String MDC_CORRELATION_ID = "correlationId";
    static final String MDC_MEMBER_ID = "memberId";

    private static void put(String key, String value) {
        if (value != null) {
            org.slf4j.MDC.put(key, value);
        }
    }

    public boolean handles(String type) {
        return handlerFor(type) != null;
    }

    private EventHandler handlerFor(String type) {
        EventHandler exact = type == null ? null : byType.get(type);
        if (exact != null || type == null) {
            return exact;
        }
        for (Map.Entry<String, EventHandler> e : byPrefix.entrySet()) {
            if (type.startsWith(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }
}
