package io.loyaltyhub.reward.domain;

/** Ciclo di vita di un premio: macchina a stati comune degli oggetti governati (docs/03 §3.6, docs/06 §7). */
public enum RewardStatus {
    DRAFT,
    IN_REVIEW,
    REJECTED,
    LIVE,
    PAUSED,
    ENDED,
    ARCHIVED
}
