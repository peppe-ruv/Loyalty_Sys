package io.loyaltyhub.common.audit;

/** Payload dell'audit (docs/05 §6). {@code before}/{@code after} contengono solo i campi cambiati. */
public record AuditEntry(
        String service,
        String entityType,
        String entityId,
        Action action,
        String summary,
        Object before,
        Object after
) {
    public enum Action {
        CREATE, UPDATE, DELETE, TRANSITION, ADJUST, JOB, RESET
    }
}
