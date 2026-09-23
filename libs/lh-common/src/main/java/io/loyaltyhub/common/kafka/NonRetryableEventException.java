package io.loyaltyhub.common.kafka;

/**
 * Evento che non potrà mai essere elaborato così com'è (es. {@code COUPON_POOL_EMPTY}, docs/servizi/reward-service.md
 * §5): nessun nuovo tentativo, va subito in DLQ con {@code lh-error-code} = {@link #code()}.
 */
public class NonRetryableEventException extends RuntimeException {

    private final String code;

    public NonRetryableEventException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
