package io.loyaltyhub.engagement.messaging;

import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.common.inbox.EventRouter;
import io.loyaltyhub.common.inbox.IdempotentHandler;
import io.loyaltyhub.common.metrics.LhMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Router degli eventi di engagement (docs/06 §5): solo i propri handler, corretto anche nell'hub (ADR-006). */
@Configuration
public class EngagementRouting {

    @Bean("engagementEventRouter")
    public EventRouter engagementEventRouter(FactHandler facts, MessageSendHandler messageSend, IdempotentHandler idempotent,
                                             LhMetrics metrics) {
        return new EventRouter("lh-engagement", List.<EventHandler>of(facts, messageSend), idempotent, metrics);
    }
}
