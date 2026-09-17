package io.loyaltyhub.common.event;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Payload dell'evento canonico "azione premiante" (RI-01, RI-02, RI-08).
 * Viaggia come {@code data} di un CloudEvent di tipo {@link EventTypes#ACTION_V1}.
 * Le azioni esterne e interne hanno la stessa forma (ADR-008, ADR-009).
 */
public record RewardingAction(
        @NotBlank String actionType,
        @NotBlank String idempotencyKey,
        String externalRef,
        @NotNull Instant occurredAt,
        /** Se valorizzato, l'evento è uno storno della azione con questa chiave (RI-08). */
        String reversalOf,
        Map<String, Object> attributes
) {
    public boolean isReversal() {
        return reversalOf != null && !reversalOf.isBlank();
    }

    public BigDecimal decimalAttribute(String name) {
        Object v = attributes == null ? null : attributes.get(name);
        return v == null ? null : new BigDecimal(v.toString());
    }
}
