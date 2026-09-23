package io.loyaltyhub.ingestion.api;

/** Corpo di {@code PUT /v1/internal-mappings/{factType}}: abilita o disabilita una mappatura del ponte. */
public record InternalMappingRequest(Boolean enabled) {
}
