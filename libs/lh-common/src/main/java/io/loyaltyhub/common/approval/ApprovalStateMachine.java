package io.loyaltyhub.common.approval;

import io.loyaltyhub.common.web.LhException;

/**
 * Macchina a stati del ciclo di vita degli oggetti governati (docs/03 §3.6).
 * Transizioni non ammesse ⇒ {@code 409 conflict}; {@code REJECT} richiede un commento.
 * {@code PUBLISH} da {@code DRAFT} è ammesso solo se la policy non richiede approvazione.
 */
public final class ApprovalStateMachine {

    private ApprovalStateMachine() {
    }

    /**
     * Applica la transizione e ritorna il nuovo stato.
     *
     * @param requiresApproval la policy dell'oggetto richiede approvazione (docs/06 §7)
     * @param hasComment       è stato fornito un commento (obbligatorio per {@code REJECT})
     */
    public static ApprovalStatus next(ApprovalStatus from, ApprovalAction action,
                                      boolean requiresApproval, boolean hasComment) {
        return switch (action) {
            case SUBMIT -> require(from, ApprovalStatus.DRAFT, action, ApprovalStatus.IN_REVIEW);
            case APPROVE -> require(from, ApprovalStatus.IN_REVIEW, action, ApprovalStatus.APPROVED);
            case REJECT -> {
                requireState(from, ApprovalStatus.IN_REVIEW, action);
                if (!hasComment) {
                    throw LhException.validation("REJECT_COMMENT_REQUIRED", "Il rifiuto richiede un commento");
                }
                yield ApprovalStatus.DRAFT;
            }
            case PUBLISH -> {
                if (from == ApprovalStatus.APPROVED) {
                    yield ApprovalStatus.LIVE;
                }
                if (from == ApprovalStatus.DRAFT) {
                    if (requiresApproval) {
                        throw LhException.conflict("APPROVAL_REQUIRED",
                                "L'oggetto richiede approvazione prima della pubblicazione");
                    }
                    yield ApprovalStatus.LIVE;
                }
                throw invalid(from, action);
            }
            case PAUSE -> require(from, ApprovalStatus.LIVE, action, ApprovalStatus.PAUSED);
            case RESUME -> require(from, ApprovalStatus.PAUSED, action, ApprovalStatus.LIVE);
            case END -> {
                if (from == ApprovalStatus.LIVE || from == ApprovalStatus.PAUSED) {
                    yield ApprovalStatus.ENDED;
                }
                throw invalid(from, action);
            }
            case ARCHIVE -> {
                if (from == ApprovalStatus.ENDED || from == ApprovalStatus.DRAFT) {
                    yield ApprovalStatus.ARCHIVED;
                }
                throw invalid(from, action);
            }
        };
    }

    private static ApprovalStatus require(ApprovalStatus from, ApprovalStatus expected,
                                          ApprovalAction action, ApprovalStatus to) {
        requireState(from, expected, action);
        return to;
    }

    private static void requireState(ApprovalStatus from, ApprovalStatus expected, ApprovalAction action) {
        if (from != expected) {
            throw invalid(from, action);
        }
    }

    private static LhException invalid(ApprovalStatus from, ApprovalAction action) {
        return LhException.conflict("INVALID_TRANSITION",
                "Transizione " + action + " non ammessa dallo stato " + from);
    }
}
