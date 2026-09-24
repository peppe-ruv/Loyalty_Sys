package io.loyaltyhub.gamification.api;

import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.gamification.application.ContestAdminService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Aiuti demo di gamification (docs/servizi/gamification-service.md §3 "Demo"; F-IW-08, BO-14, BO-30): istante piantato
 * e fine concorsi con una data di riferimento {@code asOf}. Solo {@code ADMIN}, solo profilo {@code demo}; fuori demo la
 * fine concorsi gira schedulata ({@link io.loyaltyhub.gamification.application.GamificationJobs}).
 */
@RestController
@RequestMapping("/v1/demo")
@Profile("demo")
public class GamificationDemoController {

    public record PlantRequest(String prizeCode) {
    }

    public record PlantOutcome(String instantId, String prizeCode, String prizeName, Instant instantAt) {
    }

    public record CloseContestsOutcome(String job, Instant asOf, int contests, int voided) {
    }

    private static final ZoneId ZONE = ZoneId.of("Europe/Rome");

    private final ContestAdminService admin;
    private final Clock clock;

    public GamificationDemoController(ContestAdminService admin, Clock clock) {
        this.admin = admin;
        this.clock = clock;
    }

    /** Anticipa a {@code now − 1 s} l'ultimo istante aperto del premio: la prossima giocata vince. */
    @PostMapping("/contests/{id}/plant-instant")
    @RequiresRole({Role.ADMIN})
    public PlantOutcome plantInstant(@PathVariable String id, @RequestBody(required = false) PlantRequest r) {
        ContestAdminService.PlantResult p = admin.plantInstant(id, r == null ? null : r.prizeCode());
        return new PlantOutcome(p.instantId(), p.prizeCode(), p.prizeName(), p.instantAt());
    }

    /** Concorsi {@code LIVE} con {@code end_at <= asOf} → {@code ENDED}, istanti aperti → {@code VOID}. */
    @PostMapping("/jobs/close-contests")
    @RequiresRole({Role.ADMIN})
    public CloseContestsOutcome closeContests(@RequestParam(required = false) String asOf) {
        Instant at = asOf(asOf);
        ContestAdminService.CloseResult r = admin.closeEnded(at);
        return new CloseContestsOutcome("close-contests", at, r.contests(), r.voided());
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
