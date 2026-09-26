package io.loyaltyhub.insight.application;

import io.loyaltyhub.insight.infra.AuditRepository;
import io.loyaltyhub.insight.infra.EventStoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retention (RNF-07, docs/servizi/insight-service.md §5): l'event store tiene gli eventi degli ultimi
 * {@code max-age-days} giorni o le {@code max-rows} righe più recenti (il minore); l'audit 180 giorni.
 * Le metriche ({@code metric_daily}) sono illimitate. Job orario.
 */
@Component
@org.springframework.context.annotation.Lazy(false) // docs/06 §5: attivo anche con lazy-initialization (profilo free)
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final EventStoreRepository events;
    private final AuditRepository audits;
    private final int maxAgeDays;
    private final int maxRows;
    private final int auditMaxAgeDays;

    public RetentionJob(EventStoreRepository events, AuditRepository audits,
                        @Value("${loyaltyhub.insight.retention.max-age-days:14}") int maxAgeDays,
                        @Value("${loyaltyhub.insight.retention.max-rows:200000}") int maxRows,
                        @Value("${loyaltyhub.insight.retention.audit-max-age-days:180}") int auditMaxAgeDays) {
        this.events = events;
        this.audits = audits;
        this.maxAgeDays = maxAgeDays;
        this.maxRows = maxRows;
        this.auditMaxAgeDays = auditMaxAgeDays;
    }

    @Scheduled(cron = "${loyaltyhub.insight.retention.cron:0 20 * * * *}")
    public void purge() {
        int byAge = events.deleteOlderThan(maxAgeDays);
        int byRows = events.trimToMaxRows(maxRows);
        int auditByAge = audits.deleteOlderThan(auditMaxAgeDays);
        if (byAge + byRows + auditByAge > 0) {
            log.debug("Retention: event store {} per età (>{}g) + {} per soglia (>{} righe); audit {} (>{}g)",
                    byAge, maxAgeDays, byRows, maxRows, auditByAge, auditMaxAgeDays);
        }
    }
}
