package io.loyaltyhub.common.approval;

import java.time.Instant;
import java.util.UUID;

/** Riga di {@code approval_history} (docs/06 §1): chi, quando, da/verso, azione, commento. */
public record ApprovalHistory(
        UUID id,
        String entityType,
        String entityId,
        ApprovalStatus fromStatus,
        ApprovalStatus toStatus,
        ApprovalAction action,
        String actor,
        String comment,
        Instant createdAt
) {
}
