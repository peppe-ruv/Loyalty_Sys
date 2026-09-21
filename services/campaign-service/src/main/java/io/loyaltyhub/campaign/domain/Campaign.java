package io.loyaltyhub.campaign.domain;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * Campagna del motore regole (docs/servizi/campaign-service.md §2, docs/03 §3.2).
 * {@code audience}, {@code conditions}, {@code effects}, {@code limits}, {@code schedule} restano JSON
 * grezzo: il motore li interpreta senza vincolarne la forma (le nuove opzioni non richiedono migrazioni).
 */
public record Campaign(
        String id,
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
        int priority,
        String exclusiveGroup,
        boolean visibleInPortal,
        boolean system,
        boolean requiresLegal,
        List<String> labels,
        CampaignStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public boolean triggersOn(String actionType) {
        return triggerActionTypes.contains(actionType);
    }
}
