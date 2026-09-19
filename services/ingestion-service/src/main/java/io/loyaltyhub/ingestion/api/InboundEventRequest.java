package io.loyaltyhub.ingestion.api;

import tools.jackson.databind.JsonNode;

/**
 * CloudEvent in ingresso dalle fonti esterne (docs/05 §2). In ingresso sono obbligatori solo
 * {@code specversion, id, source, type, subject, time, data}; gli attributi {@code lh*} li aggiunge ingestion.
 */
public record InboundEventRequest(
        String specversion,
        String id,
        String source,
        String type,
        String subject,
        String time,
        JsonNode data
) {
}
