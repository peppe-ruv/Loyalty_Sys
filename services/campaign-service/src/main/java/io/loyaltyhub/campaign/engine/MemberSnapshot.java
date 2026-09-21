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
        LocalDate birthDate
) {
    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
