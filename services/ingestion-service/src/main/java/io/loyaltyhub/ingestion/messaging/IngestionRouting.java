package io.loyaltyhub.ingestion.messaging;

import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.common.inbox.EventRouter;
import io.loyaltyhub.common.inbox.IdempotentHandler;
import io.loyaltyhub.common.metrics.LhMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Router degli eventi del servizio (docs/06 §5), costruito con i soli handler di ingestion. Esplicito così
 * il servizio resta corretto anche quando gira <em>consolidato</em> con altri servizi nello stesso JVM
 * (deploy demo, docs/13 ADR-006): ogni servizio instrada solo verso i propri handler, sotto il proprio gruppo.
 */
@Configuration
public class IngestionRouting {

    @Bean("ingestionEventRouter")
    public EventRouter ingestionEventRouter(ArchetypeProbeHandler probe, FactsHandler facts, IdempotentHandler idempotent,
                                           LhMetrics metrics) {
        return new EventRouter("lh-ingestion", List.<EventHandler>of(probe, facts), idempotent, metrics);
    }
}
