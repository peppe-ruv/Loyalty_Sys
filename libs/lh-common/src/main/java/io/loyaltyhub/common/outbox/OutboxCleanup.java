package io.loyaltyhub.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

/** Pulizia schedulata (RNF-07, docs/06 §4): rimuove le righe {@code outbox} pubblicate da oltre 24 h. */
public class OutboxCleanup {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanup.class);

    private final JdbcClient jdbc;

    public OutboxCleanup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "${loyaltyhub.outbox.cleanup-cron:0 15 * * * *}")
    public void purgePublished() {
        int deleted = jdbc.sql("DELETE FROM outbox WHERE published_at IS NOT NULL AND published_at < now() - interval '24 hours'")
                .update();
        if (deleted > 0) {
            log.debug("Outbox: rimosse {} righe pubblicate da oltre 24 h", deleted);
        }
    }
}
