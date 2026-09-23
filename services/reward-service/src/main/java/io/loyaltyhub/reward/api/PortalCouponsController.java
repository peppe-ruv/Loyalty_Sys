package io.loyaltyhub.reward.api;

import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.reward.application.CouponService;
import io.loyaltyhub.reward.domain.CouponStatus;
import io.loyaltyhub.reward.domain.Reward;
import io.loyaltyhub.reward.infra.RewardRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** {@code GET /v1/portal/coupons?memberId=} (docs/servizi/reward-service.md §3 "Portale"; PT-13). */
@RestController
public class PortalCouponsController {

    public record PortalCoupon(String code, String rewardCode, String rewardName, CouponStatus status,
                               Instant issuedAt, Instant expiresAt, String origin) {
    }

    private final CouponService coupons;
    private final RewardRepository rewards;

    public PortalCouponsController(CouponService coupons, RewardRepository rewards) {
        this.coupons = coupons;
        this.rewards = rewards;
    }

    @GetMapping("/v1/portal/coupons")
    public List<PortalCoupon> coupons(@RequestParam(required = false) String memberId) {
        if (memberId == null || memberId.isBlank()) {
            throw LhException.badRequest("memberId è obbligatorio");
        }
        Map<String, String> names = rewards.findAll().stream()
                .collect(Collectors.toMap(Reward::code, Reward::name, (a, b) -> a));
        return coupons.memberCoupons(memberId).stream()
                .map(c -> new PortalCoupon(c.code(), c.rewardCode(), names.getOrDefault(c.rewardCode(), c.poolName()),
                        c.status(), c.issuedAt(), c.expiresAt(), c.origin()))
                .toList();
    }
}
