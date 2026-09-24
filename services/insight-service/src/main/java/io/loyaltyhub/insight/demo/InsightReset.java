package io.loyaltyhub.insight.demo;

import io.loyaltyhub.common.demo.DemoResettable;
import io.loyaltyhub.insight.infra.AuditRepository;
import io.loyaltyhub.insight.infra.DlqRepository;
import io.loyaltyhub.insight.infra.EventStoreRepository;
import io.loyaltyhub.insight.infra.TopicStatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reset demo di insight (docs/servizi/insight-service.md §6): svuota l'event store e le statistiche per topic;
 * si ripopolano da soli con i primi eventi. Nessun evento pre-caricato. Lo storico sintetico di
 * {@code metric_daily} è ricreato da {@link InsightSyntheticSeeder} (M2.4). Svuota anche audit e DLQ (M7.3).
 */
@Component
@Profile("demo")
public class InsightReset implements DemoResettable {

    private static final Logger log = LoggerFactory.getLogger(InsightReset.class);

    private final EventStoreRepository events;
    private final TopicStatRepository topicStats;
    private final AuditRepository audits;
    private final DlqRepository dlq;

    public InsightReset(EventStoreRepository events, TopicStatRepository topicStats, AuditRepository audits,
                        DlqRepository dlq) {
        this.events = events;
        this.topicStats = topicStats;
        this.audits = audits;
        this.dlq = dlq;
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
        audits.deleteAll();
        dlq.deleteAll();
        log.info("Seed insight ripristinato (profilo demo): event store, statistiche, audit e DLQ svuotati");
    }
}
