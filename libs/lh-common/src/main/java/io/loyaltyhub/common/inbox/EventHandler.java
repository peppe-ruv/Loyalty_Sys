package io.loyaltyhub.common.inbox;

import com.fasterxml.jackson.databind.JsonNode;
import io.loyaltyhub.common.event.LhEvent;

import java.util.Set;

/**
 * Handler di dominio per uno o più {@code type} evento (docs/06 §5): niente logica nel listener,
 * il router instrada qui. L'esecuzione è già dentro la transazione idempotente di {@link IdempotentHandler}.
 */
public interface EventHandler {

    /** {@code type} evento gestiti da questo handler (docs/05). */
    Set<String> handledTypes();

    /** Applica l'effetto sull'evento. Le scritture su Kafka passano dall'{@code OutboxWriter}. */
    void handle(LhEvent<JsonNode> event);
}
