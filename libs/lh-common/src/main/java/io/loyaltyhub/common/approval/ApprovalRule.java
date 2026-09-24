package io.loyaltyhub.common.approval;

import io.loyaltyhub.common.web.Role;

/**
 * Esito della policy per un oggetto (docs/06 §7): serve approvazione? chi approva? {@code reason} spiega la regola
 * (es. "budget oltre 100 000 punti") nella coda di BO-21.
 */
public record ApprovalRule(boolean required, Role approverRole, String reason) {

    public static final ApprovalRule NONE = new ApprovalRule(false, null, "pubblicazione diretta");

    public static ApprovalRule legal(String reason) {
        return new ApprovalRule(true, Role.LEGAL, reason);
    }
}
