package io.loyaltyhub.common.inbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Pulizia schedulata di {@code processed_event} (RNF-07, docs/06 §4): rimuove le righe elaborate da oltre
 * {@code loyaltyhub.processed-event.retention-days} giorni (default 14). La memoria della deduplica resta ben oltre la
 * retention dei topic (3 giorni, docs/05 §1): nessun messaggio ancora riconsegnabile perde la sua riga.
 * SPEC-GAP: Q-P4 — docs/11 §4 dice 7 giorni, docs/06 §4 14: vale il valore più prudente (14).
 */
public class ProcessedEventCleanup {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventCleanup.class);

    private final JdbcClient jdbc;
    private final int retentionDays;

    public ProcessedEventCleanup(JdbcClient jdbc, int retentionDays) {
        this.jdbc = jdbc;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${loyaltyhub.processed-event.cleanup-cron:0 20 3 * * *}")
    public void purgeOld() {
        int deleted = jdbc.sql("DELETE FROM processed_event WHERE processed_at < now() - make_interval(days => ?)")
                .param(retentionDays)
                .update();
        if (deleted > 0) {
            log.debug("processed_event: rimosse {} righe più vecchie di {} giorni", deleted, retentionDays);
        }
    }
}
