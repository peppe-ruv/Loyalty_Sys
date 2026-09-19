package io.loyaltyhub.common.kafka;

/** Catena di eventi interni troppo profonda ({@code lhhop > 3}, docs/05 §7): il messaggio va in DLQ con {@code LOOP_GUARD}. */
public class LoopGuardException extends RuntimeException {
    public LoopGuardException(String message) {
        super(message);
    }
}
