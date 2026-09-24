package io.loyaltyhub.common.approval;

import java.time.Instant;
import java.util.List;

/**
 * Elemento della coda approvazioni nel formato comune (docs/06 §7): {@code GET /v1/approvals} di ogni servizio
 * proprietario; BO-21 li aggrega. {@code status} e {@code decidedBy/decidedAt/comment} servono alla scheda
 * «Inviate da me» (oggetti inviati e poi decisi).
 */
public record ApprovalItem(
        String entityType,
        String id,
        String code,
        String name,
        String status,
        String submittedBy,
        Instant submittedAt,
        String requiredRole,
        String reason,
        String summary,
        String decidedBy,
        Instant decidedAt,
        String decision,
        String comment
) {

    /**
     * Elemento dallo storico ({@code history} dal più recente): ultimo {@code SUBMIT} e, se successiva, l'ultima
     * decisione ({@code APPROVE}/{@code REJECT}) con il suo commento.
     */
    public static ApprovalItem of(String entityType, String id, String code, String name, String status,
                                  ApprovalRule rule, String summary, List<ApprovalHistory> history) {
        ApprovalHistory submit = history.stream().filter(h -> h.action() == ApprovalAction.SUBMIT).findFirst().orElse(null);
        ApprovalHistory decision = history.stream()
                .filter(h -> h.action() == ApprovalAction.APPROVE || h.action() == ApprovalAction.REJECT)
                .filter(h -> submit == null || !h.createdAt().isBefore(submit.createdAt()))
                .findFirst().orElse(null);
        return new ApprovalItem(entityType, id, code, name, status,
                submit == null ? null : submit.actor(), submit == null ? null : submit.createdAt(),
                rule.approverRole() == null ? null : rule.approverRole().name(), rule.reason(), summary,
                decision == null ? null : decision.actor(), decision == null ? null : decision.createdAt(),
                decision == null ? null : decision.action().name(), decision == null ? null : decision.comment());
    }
}
