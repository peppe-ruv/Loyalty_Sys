package io.loyaltyhub.ledger.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Lavori pianificati del ledger: rilascio dei punti in sospeso (RF-66) e scadenza dei punti PREMIO (RF-09).
 * In cluster gira su una sola replica (lock ShedLock/leader election da configurare in Helm: {@code ledger.jobs.leader}).
 */
@Component
public class LedgerJobs {
    private static final Logger log = LoggerFactory.getLogger(LedgerJobs.class);
    private final LedgerService ledger;

    public LedgerJobs(LedgerService ledger) { this.ledger = ledger; }

    @Scheduled(cron = "${ledger.jobs.release-cron:0 */15 * * * *}")
    public void releasePending() {
        int n = ledger.releasePending(Instant.now());
        if (n > 0) log.info("released {} pending movements", n);
    }

    @Scheduled(cron = "${ledger.jobs.expiry-cron:0 30 2 * * *}")
    public void expirePoints() {
        int n = ledger.expire(Instant.now());
        if (n > 0) log.info("expired {} movements", n);
    }
}
