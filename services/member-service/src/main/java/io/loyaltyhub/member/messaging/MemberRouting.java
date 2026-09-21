package io.loyaltyhub.member.messaging;

import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.common.inbox.EventRouter;
import io.loyaltyhub.common.inbox.IdempotentHandler;
import io.loyaltyhub.common.metrics.LhMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Router degli eventi di member (docs/06 §5): solo i propri handler, corretto anche in modalità consolidata (ADR-006). */
@Configuration
public class MemberRouting {

    @Bean("memberEventRouter")
    public EventRouter memberEventRouter(MemberStatsHandler stats, MemberProjectionHandler projection,
                                         IdempotentHandler idempotent, LhMetrics metrics) {
        return new EventRouter("lh-member", List.<EventHandler>of(stats, projection), idempotent, metrics);
    }
}
