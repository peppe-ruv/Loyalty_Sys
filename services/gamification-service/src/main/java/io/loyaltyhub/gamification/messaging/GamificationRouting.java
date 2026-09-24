package io.loyaltyhub.gamification.messaging;

import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.common.inbox.EventRouter;
import io.loyaltyhub.common.inbox.IdempotentHandler;
import io.loyaltyhub.common.metrics.LhMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Router degli eventi di gamification (docs/06 §5): solo i propri handler, corretto anche nell'hub (ADR-006). */
@Configuration
public class GamificationRouting {

    @Bean("gamificationEventRouter")
    public EventRouter gamificationEventRouter(MemberSnapshotHandler members, PlaysGrantHandler playsGrant,
                                               AchievementActionHandler achievements, BadgeAwardHandler badges,
                                               IdempotentHandler idempotent, LhMetrics metrics) {
        return new EventRouter("lh-gamification", List.<EventHandler>of(members, playsGrant, achievements, badges),
                idempotent, metrics);
    }
}
