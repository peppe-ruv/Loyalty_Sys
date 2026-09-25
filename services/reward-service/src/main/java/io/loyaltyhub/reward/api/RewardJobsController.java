package io.loyaltyhub.reward.api;

import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.reward.application.CouponService;
import io.loyaltyhub.reward.application.RedemptionService;
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
import java.time.temporal.ChronoUnit;

/**
 * Job demo di reward (docs/servizi/reward-service.md §3 "Demo"; BO-30): scadenza coupon e timeout delle richieste
 * con una data di riferimento {@code asOf}. Solo {@code ADMIN}, solo profilo {@code demo}; fuori demo gira schedulato ({@link io.loyaltyhub.reward.application.RewardJobs}).
 */
@RestController
@RequestMapping("/v1/demo/jobs")
@Profile("demo")
public class RewardJobsController {

    public record CouponJobOutcome(String job, Instant asOf, int coupons) {
    }

    public record RedemptionJobOutcome(String job, Instant asOf, int redemptions) {
    }

    private static final ZoneId ZONE = ZoneId.of("Europe/Rome");
    /**
     * Ultimo istante del giorno alla precisione del database (microsecondi): 23:59:59.999999. Con {@link LocalTime#MAX}
     * (nanosecondi) il timestamp verrebbe arrotondato alla mezzanotte successiva e il job scadrebbe anche i coupon che
     * scadono alle 00:00 del giorno dopo (come {@code ExpiryPolicy.LAST_INSTANT} del wallet).
     */
    static final LocalTime LAST_INSTANT = LocalTime.MAX.truncatedTo(ChronoUnit.MICROS);

    private final CouponService coupons;
    private final RedemptionService redemptions;
    private final Clock clock;

    public RewardJobsController(CouponService coupons, RedemptionService redemptions, Clock clock) {
        this.coupons = coupons;
        this.redemptions = redemptions;
        this.clock = clock;
    }

    /** Richieste {@code PENDING} da oltre 10 minuti rispetto ad {@code asOf} → {@code REJECTED (TIMEOUT)}. */
    @PostMapping("/timeout-redemptions")
    @RequiresRole({Role.ADMIN})
    public RedemptionJobOutcome timeoutRedemptions(@RequestParam(required = false) String asOf) {
        Instant at = asOf(asOf);
        return new RedemptionJobOutcome("timeout-redemptions", at, redemptions.timeoutPending(at));
    }

    @PostMapping("/expire-coupons")
    @RequiresRole({Role.ADMIN})
    public CouponJobOutcome expireCoupons(@RequestParam(required = false) String asOf) {
        Instant at = asOf(asOf);
        return new CouponJobOutcome("expire-coupons", at, coupons.expire(at));
    }

    /** Come il wallet: vuoto → adesso; con la {@code T} un istante ISO; una data pura è la fine di quel giorno. */
    private Instant asOf(String value) {
        if (value == null || value.isBlank()) {
            return clock.instant();
        }
        if (value.contains("T")) {
            return Instant.parse(value);
        }
        return LocalDate.parse(value).atTime(LAST_INSTANT).atZone(ZONE).toInstant();
    }
}
