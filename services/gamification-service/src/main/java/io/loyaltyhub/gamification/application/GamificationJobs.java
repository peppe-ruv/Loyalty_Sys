package io.loyaltyhub.gamification.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Job schedulati di gamification (docs/servizi/gamification-service.md §5): fine concorsi ogni 5 minuti. Attivi solo
 * con {@code loyaltyhub.jobs.enabled=true} (spenti in demo: lì si lanciano da BO-30 con
 * {@code /v1/demo/jobs/close-contests}). Usano l'istante corrente come {@code asOf}; attore {@code system}.
 */
@Component
@ConditionalOnProperty(name = "loyaltyhub.jobs.enabled", havingValue = "true")
public class GamificationJobs {

    private final ContestAdminService contests;
    private final Clock clock;

    public GamificationJobs(ContestAdminService contests, Clock clock) {
        this.contests = contests;
        this.clock = clock;
    }

    @Scheduled(cron = "0 */5 * * * *")
    public void closeContests() {
        contests.closeEnded(clock.instant());
    }
}
