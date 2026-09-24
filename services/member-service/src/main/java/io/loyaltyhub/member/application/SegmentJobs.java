package io.loyaltyhub.member.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Ricalcolo periodico dei segmenti dinamici (docs/servizi/member-service.md §5): ogni 15 minuti, <em>solo</em> se ci
 * sono state variazioni in {@code member_stats}/{@code member_projection} ({@link SegmentChangeTracker}). Attivo solo
 * con {@code loyaltyhub.jobs.enabled=true} (spento in demo, come negli altri servizi: lì si lancia da BO-30 con
 * {@code /v1/demo/jobs/refresh-segments}). Attore {@code system}.
 */
@Component
@ConditionalOnProperty(name = "loyaltyhub.jobs.enabled", havingValue = "true")
public class SegmentJobs {

    private final SegmentRefresher refresher;
    private final SegmentChangeTracker changes;
    private final Clock clock;

    public SegmentJobs(SegmentRefresher refresher, SegmentChangeTracker changes, Clock clock) {
        this.refresher = refresher;
        this.changes = changes;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${loyaltyhub.member.segments.refresh-interval-ms:900000}",
            initialDelayString = "${loyaltyhub.member.segments.refresh-interval-ms:900000}")
    public void refreshSegments() {
        if (changes.consume()) {
            refresher.refreshAll(clock.instant(), "system", false);
        }
    }
}
