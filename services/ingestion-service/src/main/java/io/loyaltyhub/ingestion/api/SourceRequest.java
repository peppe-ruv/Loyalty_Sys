package io.loyaltyhub.ingestion.api;

import java.util.List;

/**
 * Corpo di {@code PUT /v1/sources/{code}} (docs/servizi/ingestion-service.md §3: "abilitazione, tipi ammessi"). I campi
 * assenti restano invariati; {@code allowedTypes} vuoto = tutti i tipi.
 */
public record SourceRequest(Boolean enabled, List<String> allowedTypes) {
}
