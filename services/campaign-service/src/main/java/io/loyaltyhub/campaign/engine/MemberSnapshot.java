package io.loyaltyhub.campaign.engine;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Snapshot locale del membro per il motore (docs/servizi/campaign-service.md §2, docs/03 §3.3). */
public record MemberSnapshot(
        String memberId,
        String status,
        String tier,
        List<String> segments,
        List<String> labels,
        JsonNode attributes,
        Instant registeredAt,
        LocalDate birthDate,
        Integer birthYear,
        String province
) {
    /**
     * Snapshot da fatti {@code :1} (data di nascita completa): l'anno si ricava dalla data, la provincia è assente.
     * Con {@code member.*:2} (ADR-032) arrivano solo {@code birthYear} e {@code province}.
     */
    public MemberSnapshot(String memberId, String status, String tier, List<String> segments, List<String> labels,
                          JsonNode attributes, Instant registeredAt, LocalDate birthDate) {
        this(memberId, status, tier, segments, labels, attributes, registeredAt, birthDate,
                birthDate == null ? null : birthDate.getYear(), null);
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
