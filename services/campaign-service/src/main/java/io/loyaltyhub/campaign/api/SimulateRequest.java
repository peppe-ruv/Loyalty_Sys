package io.loyaltyhub.campaign.api;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Corpo di {@code POST /v1/campaigns/simulate} (docs/servizi/campaign-service.md §3): esegue il motore
 * senza scrivere. {@code memberOverride} permette di provare tier/segmenti/attributi ipotetici.
 */
public record SimulateRequest(
        ActionInput action,
        String memberId,
        MemberOverride memberOverride,
        List<String> campaignIds
) {
    public record ActionInput(String type, String time, String source, JsonNode data) {
    }

    public record MemberOverride(String tier, List<String> segments, JsonNode attributes) {
    }
}
