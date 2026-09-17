package it.iren.loyalty.rulesengine.api;

import it.iren.loyalty.common.event.RewardingAction;
import it.iren.loyalty.rulesengine.campaign.CampaignEvaluator;
import it.iren.loyalty.rulesengine.messaging.ActionConsumer;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Valutazione delle campagne senza effetti collaterali (RF-127): il decision-service la chiama per ottenere i
 * candidati (effetti che scatterebbero) e decide cosa applicare. Il rules-engine resta l'unico interprete delle
 * regole WHEN/IF/THEN; il decision-service non le duplica.
 */
@RestController
@RequestMapping("/v1/evaluations")
public class EvaluationController {
    public record Request(String memberId, @Valid RewardingAction action) {}

    private final ActionConsumer consumer;
    public EvaluationController(ActionConsumer consumer) { this.consumer = consumer; }

    @PostMapping
    public CampaignEvaluator.Result evaluate(@RequestBody Request r) { return consumer.evaluate(r.memberId(), r.action()); }
}
