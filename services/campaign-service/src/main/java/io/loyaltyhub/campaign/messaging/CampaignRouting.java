package io.loyaltyhub.campaign.messaging;

import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.common.inbox.EventRouter;
import io.loyaltyhub.common.inbox.IdempotentHandler;
import io.loyaltyhub.common.metrics.LhMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Router degli eventi di campaign (docs/06 §5): solo i propri handler, corretto anche in modalità consolidata (ADR-006). */
@Configuration
public class CampaignRouting {

    @Bean("campaignEventRouter")
    public EventRouter campaignEventRouter(CampaignEvaluationHandler evaluation, MemberSnapshotHandler snapshot,
                                           CampaignTotalsHandler totals, IdempotentHandler idempotent, LhMetrics metrics) {
        return new EventRouter("lh-campaign", List.<EventHandler>of(evaluation, snapshot, totals), idempotent, metrics);
    }
}
