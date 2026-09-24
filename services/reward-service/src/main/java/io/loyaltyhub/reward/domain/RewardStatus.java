package io.loyaltyhub.reward.domain;

/**
 * Ciclo di vita di un premio: macchina a stati comune degli oggetti governati (docs/03 §3.6, docs/06 §7). Stessi nomi di
 * {@code ApprovalStatus}; il rifiuto riporta in {@code DRAFT} (lo stato {@code REJECTED} usato fino a M6 è migrato, V3).
 */
public enum RewardStatus {
    DRAFT,
    IN_REVIEW,
    APPROVED,
    LIVE,
    PAUSED,
    ENDED,
    ARCHIVED
}
