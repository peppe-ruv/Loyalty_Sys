package io.loyaltyhub.insight.application;

import io.loyaltyhub.insight.infra.EventStoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retention dell'event store (RNF-07, docs/servizi/insight-service.md §5): tiene gli eventi degli ultimi
 * {@code max-age-days} giorni o le {@code max-rows} righe più recenti (il minore dei due). Job orario.
 */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final EventStoreRepository events;
    private final int maxAgeDays;
    private final int maxRows;

    public RetentionJob(EventStoreRepository events,
                        @Value("${loyaltyhub.insight.retention.max-age-days:14}") int maxAgeDays,
                        @Value("${loyaltyhub.insight.retention.max-rows:200000}") int maxRows) {
        this.events = events;
        this.maxAgeDays = maxAgeDays;
        this.maxRows = maxRows;
    }

    @Scheduled(cron = "${loyaltyhub.insight.retention.cron:0 20 * * * *}")
    public void purge() {
        int byAge = events.deleteOlderThan(maxAgeDays);
        int byRows = events.trimToMaxRows(maxRows);
        if (byAge + byRows > 0) {
            log.debug("Retention event store: {} righe per età (>{}g), {} per soglia (>{} righe)",
                    byAge, maxAgeDays, byRows, maxRows);
        }
    }
}
