package io.loyaltyhub.rulesengine.api;

import io.loyaltyhub.common.event.RewardingAction;
import io.loyaltyhub.rulesengine.campaign.Campaign;
import io.loyaltyhub.rulesengine.campaign.CampaignEvaluator;
import io.loyaltyhub.rulesengine.campaign.CampaignSource;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Simulatore (RF-08, RF-82): dato un evento di esempio e un membro (reale o descritto a mano), mostra quali campagne
 * scattano, quali no e perché, e gli effetti; può includere campagne non ancora pubblicate passate nel corpo.
 */
@RestController
@RequestMapping("/v1/simulations")
public class SimulationController {
    public record Request(RewardingAction action, CampaignEvaluator.MemberContext member, List<Campaign> draftCampaigns, Map<String, CampaignEvaluator.Usage> usage) {}

    private final CampaignSource campaigns;
    private final CampaignEvaluator evaluator;
    public SimulationController(CampaignSource campaigns, CampaignEvaluator evaluator) { this.campaigns = campaigns; this.evaluator = evaluator; }

    @PostMapping
    public CampaignEvaluator.Result simulate(@RequestBody Request r) {
        var all = new java.util.ArrayList<>(campaigns.publishedCampaignsFor(r.action().actionType()));
        if (r.draftCampaigns() != null) all.addAll(r.draftCampaigns());
        return evaluator.evaluate(r.action(), all, r.member() == null ? CampaignEvaluator.MemberContext.simple("simulated", "BASE") : r.member(), r.usage(), Instant.now());
    }
}
