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
 * Instrada un evento all'{@link EventHandler} registrato per il suo {@code type} (docs/06 §5).
 * Un {@code type} non registrato viene <em>ignorato senza errore</em> e senza scrivere {@code processed_event}
 * (docs/05 §1). Il consumo passa dall'{@link IdempotentHandler}: doppio invio ⇒ stesso stato.
 */
public class EventRouter {

    private static final Logger log = LoggerFactory.getLogger(EventRouter.class);

    private final String consumer;
    private final Map<String, EventHandler> byType = new HashMap<>();
    private final IdempotentHandler idempotent;
    private final LhMetrics metrics;

    public EventRouter(String consumer, List<EventHandler> handlers, IdempotentHandler idempotent, LhMetrics metrics) {
        this.consumer = consumer;
        this.idempotent = idempotent;
        this.metrics = metrics;
        for (EventHandler handler : handlers) {
            for (String type : handler.handledTypes()) {
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
        EventHandler handler = byType.get(event.type());
        if (handler == null) {
            log.trace("Type non gestito da {}, ignorato: {}", consumer, event.type());
            return false;
        }
        metrics.eventConsumed(event.type());
        long start = System.nanoTime();
        try {
            return idempotent.handle(consumer, event, handler::handle);
        } finally {
            metrics.handlerTime(event.type(), System.nanoTime() - start);
        }
    }

    public boolean handles(String type) {
        return byType.containsKey(type);
    }
}
