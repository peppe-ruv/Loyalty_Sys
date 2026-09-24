package io.loyaltyhub.engagement.api;

import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.engagement.application.WebhookDispatcher;
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
 * Job demo di engagement (BO-30): invio delle consegne dei webhook dovute a una data di riferimento {@code asOf} —
 * così i ritenti a 1, 5 e 15 minuti si provano senza aspettare. Solo {@code ADMIN}, solo profilo {@code demo}; fuori
 * dalla demo lo stesso lavoro lo fa lo scheduler ogni 30 s ({@link io.loyaltyhub.engagement.application.WebhookJobs}).
 */
@RestController
@RequestMapping("/v1/demo/jobs")
@Profile("demo")
public class EngagementJobsController {

    public record WebhookJobOutcome(String job, Instant asOf, int attempted, int ok, int failed, int gaveUp) {
    }

    private static final ZoneId ZONE = ZoneId.of("Europe/Rome");

    private final WebhookDispatcher dispatcher;
    private final Clock clock;

    public EngagementJobsController(WebhookDispatcher dispatcher, Clock clock) {
        this.dispatcher = dispatcher;
        this.clock = clock;
    }

    @PostMapping("/deliver-webhooks")
    @RequiresRole({Role.ADMIN})
    public WebhookJobOutcome deliverWebhooks(@RequestParam(required = false) String asOf) {
        Instant at = asOf(asOf);
        WebhookDispatcher.RunOutcome r = dispatcher.deliverDue(at);
        return new WebhookJobOutcome("deliver-webhooks", at, r.attempted(), r.ok(), r.failed(), r.gaveUp());
    }

    /** Come gli altri servizi: vuoto → adesso; con la {@code T} un istante ISO; una data pura è la fine di quel giorno. */
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
