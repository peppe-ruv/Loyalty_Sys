package io.loyaltyhub.ingestion.messaging;

import tools.jackson.databind.JsonNode;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.inbox.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Consumatore di prova dell'archetipo (M0.5): dimostra il lato consumo della pipeline registrando la
 * ricezione delle azioni, senza produrre nulla (nessun loop). Da rimuovere quando i consumatori reali
 * (campaign, gamification, member…) arriveranno con M1.
 */
@Component
public class ArchetypeProbeHandler implements EventHandler {

    private static final Logger log = LoggerFactory.getLogger(ArchetypeProbeHandler.class);

    private final AtomicLong received = new AtomicLong();

    @Override
    public Set<String> handledTypes() {
        return Set.of(
                LhEventTypes.Action.PURCHASE_COMPLETED,
                LhEventTypes.Action.APP_LOGIN_DAILY);
    }

    @Override
    public void handle(LhEvent<JsonNode> event) {
        long n = received.incrementAndGet();
        log.debug("Archetipo: ricevuta azione {} per {} (totale {})", event.type(), event.partitionKey(), n);
    }

    /** Numero di azioni osservate (usato dai test dell'archetipo). */
    public long receivedCount() {
        return received.get();
    }
}
