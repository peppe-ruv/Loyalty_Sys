package io.loyaltyhub.gamification.domain;

import io.loyaltyhub.common.approval.ApprovalStatus;

import java.time.Instant;

/** Concorso instant win (docs/servizi/gamification-service.md §2; F-IW-01). */
public record Contest(
        String id,
        String code,
        String name,
        String description,
        String rulesText,
        String mechanic,
        Instant startAt,
        Instant endAt,
        boolean freePlayDaily,
        Integer maxPlaysPerMemberPerDay,
        Integer maxWinsPerMember,
        String distribution,
        long seed,
        Instant instantsGeneratedAt,
        ApprovalStatus status,
        long version,
        String createdBy,
        Instant updatedAt
) {
    /** Meccaniche visive (docs/02 F-IW-01: ruota, gratta, pacco). `BOX` come docs/servizi/gamification-service.md §2, che prevale su docs/08-10 (`GIFT`): SPEC-GAP Q-56. */
    // SPEC-GAP: Q-56
    public static final java.util.List<String> MECHANICS = java.util.List.of("WHEEL", "SCRATCH", "BOX");
    public static final java.util.List<String> DISTRIBUTIONS = java.util.List.of("UNIFORM", "BUSINESS_HOURS");

    /** Istanti immutabili (e premi non modificabili) dal momento in cui il concorso è andato LIVE. */
    public boolean locked() {
        return status == ApprovalStatus.LIVE || status == ApprovalStatus.PAUSED || status == ApprovalStatus.ENDED
                || status == ApprovalStatus.ARCHIVED;
    }
}
