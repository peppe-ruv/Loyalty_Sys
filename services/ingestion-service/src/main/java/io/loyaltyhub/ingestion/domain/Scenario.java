package io.loyaltyhub.ingestion.domain;

import tools.jackson.databind.JsonNode;

/**
 * Scenario demo (docs/servizi/ingestion-service.md §2, F-DEMO-04): una storiella eseguibile fatta di passi
 * {@code {delayMs, memberId, type, data, source, note, expect?}} che l'esecutore invia nella pipeline.
 */
public record Scenario(
        String code,
        String name,
        String description,
        String protagonist,
        String watch,
        JsonNode steps) {

    public int stepCount() {
        return steps == null ? 0 : steps.size();
    }
}
