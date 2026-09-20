package io.loyaltyhub.ingestion.domain;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Esecuzione di uno scenario (docs/servizi/ingestion-service.md §2). {@code results} è l'array degli esiti
 * passo per passo con il {@code correlationId} di ogni evento (BO-29). Stato: {@code RUNNING/DONE/FAILED}.
 */
public record ScenarioRun(
        String id,
        String scenarioCode,
        Instant startedAt,
        Instant finishedAt,
        String status,
        int stepsTotal,
        int stepsDone,
        String actor,
        JsonNode results) {
}
