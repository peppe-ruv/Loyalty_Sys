package io.loyaltyhub.campaign.api;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Corpo di {@code POST /v1/campaigns} (docs/servizi/campaign-service.md §3): crea in stato {@code DRAFT}. In
 * {@code PUT} {@code version} è la versione letta dall'editor: se nel frattempo è cambiata → {@code 409 VERSION_CONFLICT}
 * (docs/06 §colonne standard, M7.6); assente = nessun controllo. {@code requiresLegal} (opzionale, BO-06 sez. 1) vale
 * solo alla creazione: la policy (docs/06 §7) chiede allora l'approvazione LEGAL.
 */
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
        List<String> labels,
        Long version,
        Boolean requiresLegal
) {

    /** Senza {@code requiresLegal} (campo opzionale aggiunto): come prima, {@code false} alla creazione. */
    public CreateCampaignRequest(String code, String name, String description, String memberDescription, String icon,
                                 List<String> triggerActionTypes, JsonNode audience, JsonNode conditions,
                                 JsonNode effects, JsonNode limits, JsonNode schedule, Integer priority,
                                 String exclusiveGroup, Boolean visibleInPortal, List<String> labels, Long version) {
        this(code, name, description, memberDescription, icon, triggerActionTypes, audience, conditions, effects, limits,
                schedule, priority, exclusiveGroup, visibleInPortal, labels, version, null);
    }
}
