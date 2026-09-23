package io.loyaltyhub.reward.api;

import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.reward.application.CouponService;
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
 * Job demo di reward (docs/servizi/reward-service.md §3 "Demo"; BO-30): scadenza coupon con una data di riferimento
 * {@code asOf}. Solo {@code ADMIN}, solo profilo {@code demo}; fuori demo gira schedulato ({@link io.loyaltyhub.reward.application.RewardJobs}).
 */
@RestController
@RequestMapping("/v1/demo/jobs")
@Profile("demo")
public class RewardJobsController {

    public record CouponJobOutcome(String job, Instant asOf, int coupons) {
    }

    private static final ZoneId ZONE = ZoneId.of("Europe/Rome");

    private final CouponService coupons;
    private final Clock clock;

    public RewardJobsController(CouponService coupons, Clock clock) {
        this.coupons = coupons;
        this.clock = clock;
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
        return LocalDate.parse(value).atTime(LocalTime.MAX).atZone(ZONE).toInstant();
    }
}
