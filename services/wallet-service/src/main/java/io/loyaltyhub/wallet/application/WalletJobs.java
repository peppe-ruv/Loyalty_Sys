package io.loyaltyhub.wallet.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Job schedulati del wallet (docs/servizi/wallet-service.md §5): rilascio dei pending ogni ora, scadenze alle
 * 02:00, preavvisi alle 09:00. Attivi solo con {@code loyaltyhub.jobs.enabled=true} (spenti in demo, ADR-024:
 * in demo si lanciano a mano da BO-30 con una data di riferimento). Usano l'istante corrente come {@code asOf}.
 */
@Component
@ConditionalOnProperty(name = "loyaltyhub.jobs.enabled", havingValue = "true")
public class WalletJobs {

    private final WalletService wallet;
    private final Clock clock;

    public WalletJobs(WalletService wallet, Clock clock) {
        this.wallet = wallet;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 * * * *")
    public void releasePending() {
        wallet.releasePending(clock.instant());
    }

    @Scheduled(cron = "0 0 2 * * *")
    public void expirePoints() {
        wallet.expirePoints(clock.instant());
    }

    @Scheduled(cron = "0 0 9 * * *")
    public void expiryWarnings() {
        wallet.expiryWarnings(clock.instant());
    }
}
