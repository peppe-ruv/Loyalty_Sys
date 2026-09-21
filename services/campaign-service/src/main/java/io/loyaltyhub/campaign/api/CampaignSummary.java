package io.loyaltyhub.campaign.api;

import io.loyaltyhub.campaign.application.CampaignAdminService.Budget;
import io.loyaltyhub.campaign.domain.Campaign;
import io.loyaltyhub.campaign.infra.CounterRepository;

import java.util.List;

/** Riga di elenco campagne (docs/servizi/campaign-service.md §3): metadati + totali + budget (F-CMP-10). */
public record CampaignSummary(
        String id,
        String code,
        String name,
        String status,
        int priority,
        List<String> triggerActionTypes,
        boolean visibleInPortal,
        boolean system,
        CounterRepository.Totals totals,
        Budget budget
) {
    public static CampaignSummary of(Campaign c, CounterRepository.Totals totals, Budget budget) {
        return new CampaignSummary(c.id(), c.code(), c.name(), c.status().name(), c.priority(),
                c.triggerActionTypes(), c.visibleInPortal(), c.system(), totals, budget);
    }
}
