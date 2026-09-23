package io.loyaltyhub.reward.messaging;

import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.common.inbox.EventRouter;
import io.loyaltyhub.common.inbox.IdempotentHandler;
import io.loyaltyhub.common.metrics.LhMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Router degli eventi di reward (docs/06 §5): solo i propri handler, corretto anche in modalità consolidata (ADR-006). */
@Configuration
public class RewardRouting {

    @Bean("rewardEventRouter")
    public EventRouter rewardEventRouter(MemberSnapshotHandler members, CouponIssueHandler couponIssue,
                                         RedemptionSagaHandler saga, IdempotentHandler idempotent, LhMetrics metrics) {
        return new EventRouter("lh-reward", List.<EventHandler>of(members, couponIssue, saga), idempotent, metrics);
    }
}
