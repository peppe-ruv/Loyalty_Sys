package io.loyaltyhub.member.api;

import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.member.api.SegmentViews.RefreshJobOutcome;
import io.loyaltyhub.member.application.SegmentService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Job demo di member (docs/servizi/member-service.md §3 "Demo"; BO-30): ricalcolo dei segmenti con una data di
 * riferimento {@code asOf}. Solo {@code ADMIN}, solo profilo {@code demo}; fuori demo gira schedulato ogni 15 minuti
 * ({@link io.loyaltyhub.member.application.SegmentJobs}).
 */
@RestController
@RequestMapping("/v1/demo/jobs")
@Profile("demo")
public class MemberJobsController {

    private static final ZoneId ZONE = ZoneId.of("Europe/Rome");

    private final SegmentService segments;
    private final Clock clock;

    public MemberJobsController(SegmentService segments, Clock clock) {
        this.segments = segments;
        this.clock = clock;
    }

    @PostMapping("/refresh-segments")
    @RequiresRole({Role.ADMIN})
    public RefreshJobOutcome refreshSegments(@RequestParam(required = false) String asOf) {
        return segments.refreshAllJob(asOf(asOf));
    }

    /** Come wallet e reward: vuoto → adesso; con la {@code T} un istante ISO; una data pura è la fine di quel giorno. */
    private Instant asOf(String value) {
        if (value == null || value.isBlank()) {
            return clock.instant();
        }
        if (value.contains("T")) {
            return Instant.parse(value);
        }
        return LocalDate.parse(value).atTime(LocalTime.MAX).atZone(ZONE).toInstant();
    }
}
