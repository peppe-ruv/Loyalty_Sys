package io.loyaltyhub.common.approval;

/** Transizioni possibili sugli oggetti governati (docs/03 §3.6). */
public enum ApprovalAction {
    SUBMIT,
    APPROVE,
    REJECT,
    PUBLISH,
    PAUSE,
    RESUME,
    END,
    ARCHIVE
}
