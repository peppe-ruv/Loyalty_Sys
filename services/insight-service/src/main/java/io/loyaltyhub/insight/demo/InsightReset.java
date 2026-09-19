package io.loyaltyhub.insight.demo;

import io.loyaltyhub.common.demo.DemoResettable;
import io.loyaltyhub.insight.infra.EventStoreRepository;
import io.loyaltyhub.insight.infra.TopicStatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reset demo di insight (docs/servizi/insight-service.md §6): svuota l'event store e le statistiche per topic;
 * si ripopolano da soli con i primi eventi. Nessun evento pre-caricato in M2.1. Lo storico sintetico
 * ({@code metric_daily}), l'audit e la DLQ arrivano con le fette M2.4/M2.5.
 */
@Component
@Profile("demo")
public class InsightReset implements DemoResettable {

    private static final Logger log = LoggerFactory.getLogger(InsightReset.class);

    private final EventStoreRepository events;
    private final TopicStatRepository topicStats;

    public InsightReset(EventStoreRepository events, TopicStatRepository topicStats) {
        this.events = events;
        this.topicStats = topicStats;
    }

    @Override
    public String demoComponent() {
        return "insight";
    }

    @Override
    @Transactional
    public void resetToSeed() {
        events.deleteAll();
        topicStats.deleteAll();
        log.info("Seed insight ripristinato (profilo demo): event store e statistiche svuotati");
    }
}
