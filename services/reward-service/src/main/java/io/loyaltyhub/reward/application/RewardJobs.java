package io.loyaltyhub.reward.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Job schedulati di reward (docs/servizi/reward-service.md §5): scadenza giornaliera dei coupon. Attivi solo con
 * {@code loyaltyhub.jobs.enabled=true}; in demo si lanciano da BO-30 ({@code /v1/demo/jobs/expire-coupons}).
 */
@Component
@ConditionalOnProperty(name = "loyaltyhub.jobs.enabled", havingValue = "true")
public class RewardJobs {

    private final CouponService coupons;
    private final Clock clock;

    public RewardJobs(CouponService coupons, Clock clock) {
        this.coupons = coupons;
        this.clock = clock;
    }

    @Scheduled(cron = "0 30 2 * * *", zone = "Europe/Rome")
    public void expireCoupons() {
        coupons.expire(clock.instant());
    }
}
