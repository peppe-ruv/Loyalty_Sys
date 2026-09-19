package io.loyaltyhub.common.inbox;

import com.fasterxml.jackson.databind.JsonNode;
import io.loyaltyhub.common.event.LhEvent;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Consumer;

/**
 * Template di consumo idempotente (docs/06 §1): in una sola transazione marca {@code processed_event},
 * esegue la logica e scrive l'outbox. Un doppio invio dello stesso {@code id} lascia lo stato invariato
 * (RNF-03): la seconda volta la logica non viene eseguita.
 */
public class IdempotentHandler {

    private final ProcessedEvents processedEvents;

    public IdempotentHandler(ProcessedEvents processedEvents) {
        this.processedEvents = processedEvents;
    }

    /**
     * Esegue {@code logic} una sola volta per {@code (consumer, event.id)}.
     * @return {@code true} se la logica è stata eseguita, {@code false} se era un duplicato.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean handle(String consumer, LhEvent<JsonNode> event, Consumer<LhEvent<JsonNode>> logic) {
        boolean firstTime = processedEvents.markProcessed(consumer, event.id());
        if (!firstTime) {
            return false;
        }
        logic.accept(event);
        return true;
    }
}
