package io.loyaltyhub.campaign.api;

import tools.jackson.databind.JsonNode;

import java.util.List;

/** Corpo di {@code POST /v1/campaigns} (docs/servizi/campaign-service.md §3): crea in stato {@code DRAFT}. */
public record CreateCampaignRequest(
        String code,
        String name,
        String description,
        String memberDescription,
        String icon,
        List<String> triggerActionTypes,
        JsonNode audience,
        JsonNode conditions,
        JsonNode effects,
        JsonNode limits,
        JsonNode schedule,
        Integer priority,
        String exclusiveGroup,
        Boolean visibleInPortal,
        List<String> labels
) {
}
