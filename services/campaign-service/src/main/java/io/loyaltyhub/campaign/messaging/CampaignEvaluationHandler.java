package io.loyaltyhub.campaign.messaging;

import tools.jackson.databind.JsonNode;
import io.loyaltyhub.campaign.application.EvaluationService;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.inbox.EventHandler;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Ogni azione su {@code lh.actions.v1} passa dal motore (docs/servizi/campaign-service.md §4, §5). */
@Component
public class CampaignEvaluationHandler implements EventHandler {

    private final EvaluationService evaluation;

    public CampaignEvaluationHandler(EvaluationService evaluation) {
        this.evaluation = evaluation;
    }

    @Override
    public Set<String> handledTypes() {
        return ActionTypes.ALL;
    }

    @Override
    public void handle(LhEvent<JsonNode> event) {
        evaluation.evaluate(event);
    }
}
