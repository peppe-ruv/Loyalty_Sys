package io.loyaltyhub.insight.domain;

import java.time.Instant;

/**
 * Una voce di {@code audit_entry} vista come anello della catena del proprio servizio (F2-GRC-07, ADR-043): i campi
 * come il database li conserva, con {@code before}/{@code after} nella resa testuale di jsonb ({@code jsonb::text}),
 * cioè esattamente ciò che entra nella forma canonica di {@link AuditHashChain}.
 *
 * @param redactedAt istante dell'ultima anonimizzazione del contenuto (F-MBR-05); {@code null} se mai riscritto
 */
public record AuditChainLink(
        String service,
        long seq,
        String prevHash,
        String id,
        String eventId,
        Instant at,
        String actorRole,
        String actorName,
        String entityType,
        String entityId,
        String action,
        String correlationId,
        String summary,
        String beforeJson,
        String afterJson,
        String contentHash,
        String entryHash,
        Instant redactedAt) {
}
