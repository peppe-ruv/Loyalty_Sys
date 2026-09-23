package io.loyaltyhub.reward.domain;

import java.util.List;

/** Snapshot locale del membro per la visibilità dei premi (docs/servizi/reward-service.md §2), costruito dai fatti. */
public record MemberSnapshot(String memberId, String status, String tierCode, List<String> segments,
                             String firstName, String lastName) {
}
