package io.loyaltyhub.reward.messaging;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.common.kafka.NonRetryableEventException;
import io.loyaltyhub.reward.application.CouponService;
import io.loyaltyhub.reward.domain.Reward;
import io.loyaltyhub.reward.infra.CouponRepository;
import io.loyaltyhub.reward.infra.RewardRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Set;

/**
 * Effetto {@code coupon.issue} da una campagna (EVT-EFF-03; docs/servizi/reward-service.md §5): emette un codice del
 * pool del premio indicato, idempotente su {@code effectId}. Premio senza pool o pool vuoto → DLQ non ritentabile.
 */
@Component
public class CouponIssueHandler implements EventHandler {

    private final CouponService coupons;
    private final CouponRepository couponRepository;
    private final RewardRepository rewards;

    public CouponIssueHandler(CouponService coupons, CouponRepository couponRepository, RewardRepository rewards) {
        this.coupons = coupons;
        this.couponRepository = couponRepository;
        this.rewards = rewards;
    }

    @Override
    public Set<String> handledTypes() {
        return Set.of(LhEventTypes.Effect.COUPON_ISSUE);
    }

    @Override
    public void handle(LhEvent<JsonNode> event) {
        JsonNode d = event.data();
        String subject = event.subject();
        if (d == null || subject == null || !subject.startsWith("member:")) {
            throw new NonRetryableEventException("INVALID_EFFECT", "coupon.issue senza membro o dati: " + event.id());
        }
        String effectId = d.path("effectId").asString(event.id());
        if (couponRepository.findByEffect(effectId).isPresent()) {
            return; // già emesso per questo effetto
        }
        String rewardCode = d.path("rewardCode").asString("");
        Reward reward = rewards.findByCode(rewardCode)
                .orElseThrow(() -> new NonRetryableEventException("REWARD_NOT_FOUND", "Premio sconosciuto: " + rewardCode));
        if (reward.couponPoolId() == null) {
            throw new NonRetryableEventException("COUPON_POOL_MISSING", "Il premio " + rewardCode + " non ha un pool coupon");
        }
        String memberId = subject.substring("member:".length());
        coupons.issue(reward.couponPoolId(), memberId, rewardCode, "CAMPAIGN", null, effectId, event)
                .orElseThrow(() -> new NonRetryableEventException("COUPON_POOL_EMPTY", "Pool del premio " + rewardCode + " esaurito"));
    }
}
