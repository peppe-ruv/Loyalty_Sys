package io.loyaltyhub.common.approval;

import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.Role;

/**
 * Transizioni per ruolo sugli oggetti governati (docs/03 §3.6, docs/06 §7, docs/08 §2), sopra
 * {@link ApprovalStateMachine}:
 * <ul>
 *   <li>{@code APPROVE}/{@code REJECT} ({@code object.approve}) al ruolo della policy o ad ADMIN (override, marcato in
 *       audit), altrimenti {@code 403};</li>
 *   <li>le altre ({@code object.edit}) ad ADMIN e MARKETING, altrimenti {@code 403};</li>
 *   <li>con approvazione richiesta {@code PUBLISH} da {@code DRAFT} è {@code 409 APPROVAL_REQUIRED}: si passa da
 *       {@code SUBMIT} → {@code APPROVE} → {@code PUBLISH}; il rifiuto senza commento è {@code 422}.</li>
 *   <li>con approvazione spenta ({@code loyaltyhub.approval.enabled=false}) {@code SUBMIT} da {@code DRAFT} pubblica
 *       direttamente, come prima di M7.</li>
 * </ul>
 */
public final class GovernedTransitions {

    private GovernedTransitions() {
    }

    public static ApprovalAction parse(String action) {
        try {
            return ApprovalAction.valueOf(action == null ? "" : action.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw LhException.validation("INVALID_ACTION", "Azione sconosciuta: " + action);
        }
    }

    public static ApprovalStatus next(ApprovalStatus from, ApprovalAction action, ApprovalRule rule, boolean enabled,
                                      Role role, String comment) {
        boolean decision = action == ApprovalAction.APPROVE || action == ApprovalAction.REJECT;
        if (decision) {
            Role approver = rule.approverRole() != null ? rule.approverRole() : Role.LEGAL;
            if (role != Role.ADMIN && role != approver) {
                throw LhException.forbiddenRole("Approvare o respingere richiede il ruolo " + approver + " (o ADMIN).");
            }
        } else if (role != Role.ADMIN && role != Role.MARKETING) {
            throw LhException.forbiddenRole("Questa transizione richiede il ruolo MARKETING o ADMIN.");
        }
        boolean hasComment = comment != null && !comment.isBlank();
        if (!enabled && action == ApprovalAction.SUBMIT && from == ApprovalStatus.DRAFT) {
            return ApprovalStatus.LIVE;
        }
        return ApprovalStateMachine.next(from, action, enabled && rule.required(), hasComment);
    }

    /** L'ADMIN che decide al posto del ruolo della policy: da marcare in audit (docs/08 §2 "override"). */
    public static boolean isOverride(ApprovalAction action, ApprovalRule rule, Role role) {
        return (action == ApprovalAction.APPROVE || action == ApprovalAction.REJECT)
                && role == Role.ADMIN && rule.approverRole() != null && rule.approverRole() != Role.ADMIN;
    }
}
