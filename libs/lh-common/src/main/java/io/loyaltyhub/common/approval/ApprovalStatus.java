package io.loyaltyhub.common.approval;

/** Stati del ciclo di vita degli oggetti governati: campagne, premi, concorsi, contenuti (docs/03 §3.6). */
public enum ApprovalStatus {
    DRAFT,
    IN_REVIEW,
    APPROVED,
    LIVE,
    PAUSED,
    ENDED,
    ARCHIVED
}
