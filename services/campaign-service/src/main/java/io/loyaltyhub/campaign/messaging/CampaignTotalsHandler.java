package io.loyaltyhub.campaign.messaging;

import tools.jackson.databind.JsonNode;
import io.loyaltyhub.campaign.infra.CampaignRepository;
import io.loyaltyhub.campaign.infra.CounterRepository;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes.Fact;
import io.loyaltyhub.common.inbox.EventHandler;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Riflette i punti effettivamente accreditati in {@code campaign_totals.points_granted} dal fatto
 * {@code wallet.points.earned} (docs/servizi/campaign-service.md §4), quando porta il {@code campaignCode}.
 */
@Component
public class CampaignTotalsHandler implements EventHandler {

    private final CampaignRepository campaigns;
    private final CounterRepository counters;

    public CampaignTotalsHandler(CampaignRepository campaigns, CounterRepository counters) {
        this.campaigns = campaigns;
        this.counters = counters;
    }

    @Override
    public Set<String> handledTypes() {
        return Set.of(Fact.WALLET_POINTS_EARNED);
    }

    @Override
    public void handle(LhEvent<JsonNode> event) {
        JsonNode d = event.data();
        if (d == null || !d.has("campaignCode") || !d.has("amount")) {
            return;
        }
        String code = d.get("campaignCode").asString("");
        long amount = d.get("amount").asLong(0);
        if (code.isBlank() || amount == 0) {
            return;
        }
        campaigns.findByCode(code).ifPresent(c -> counters.addPointsGranted(c.id(), amount));
    }
}
