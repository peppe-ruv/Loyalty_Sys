package io.loyaltyhub.common.kafka;

import io.loyaltyhub.common.event.LhHeaders;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

import java.nio.charset.StandardCharsets;

/**
 * Regole comuni dei messaggi in DLQ (docs/04 §5, docs/05 §1): codice errore, ritentabilità e header
 * {@code lh-*} del record su {@code lh.dlq.v1}. Usate dal recoverer Kafka di {@link LhKafkaConfiguration} e dal
 * bus in-process dell'hub (ADR-024), così i due trasporti producono record DLQ identici.
 */
public final class DlqRecords {

    /** Tentativi totali per un errore ritentabile (1 + 2 ritenti, docs/12 accettazione M0). */
    public static final int MAX_ATTEMPTS = 3;

    private static final int MAX_MESSAGE_CHARS = 1_000;
    private static final int MAX_STACK_FRAMES = 12;
    private static final int MAX_STACK_CHARS = 4_000;

    private DlqRecords() {
    }

    /** L'eccezione applicativa dietro l'involucro del container Kafka ({@link ListenerExecutionFailedException}). */
    public static Throwable unwrap(Throwable ex) {
        Throwable t = ex;
        while (t instanceof ListenerExecutionFailedException && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    /** Codice errore del record DLQ: {@code LOOP_GUARD}, il codice di un errore non ritentabile, o il nome della classe. */
    public static String errorCode(Throwable cause) {
        if (cause instanceof LoopGuardException) {
            return "LOOP_GUARD";
        }
        if (cause instanceof NonRetryableEventException nre) {
            return nre.code();
        }
        return cause.getClass().getSimpleName();
    }

    /** Un errore non ritentabile va subito in DLQ (docs/04 §5): nessun ritentativo lo farebbe riuscire. */
    public static boolean retryable(Throwable cause) {
        return !(cause instanceof NonRetryableEventException || cause instanceof LoopGuardException);
    }

    /** Tentativi fatti prima della DLQ con la politica di lh-common: 1 per i non ritentabili, altrimenti {@link #MAX_ATTEMPTS}. */
    public static int attemptsFor(Throwable cause) {
        return retryable(cause) ? MAX_ATTEMPTS : 1;
    }

    /**
     * Header {@code lh-*} da aggiungere al record DLQ (docs/04 §5): {@code lh-original-topic}, {@code lh-consumer},
     * {@code lh-error-class}, {@code lh-error-message}, {@code lh-attempts}, più {@code lh-error-code} (docs/05) e,
     * in aggiunta, {@code lh-error-retryable} e {@code lh-error-stack} per il dettaglio di BO-27.
     */
    public static Headers headers(String originalTopic, String consumer, Throwable cause, int attempts) {
        RecordHeaders h = new RecordHeaders();
        add(h, LhHeaders.ERROR_CODE, errorCode(cause));
        add(h, LhHeaders.ORIGINAL_TOPIC, originalTopic);
        add(h, LhHeaders.CONSUMER, consumer);
        add(h, LhHeaders.ERROR_CLASS, cause.getClass().getName());
        add(h, LhHeaders.ERROR_MESSAGE, truncate(cause.getMessage() == null ? "" : cause.getMessage(), MAX_MESSAGE_CHARS));
        add(h, LhHeaders.ATTEMPTS, String.valueOf(attempts));
        add(h, LhHeaders.ERROR_RETRYABLE, String.valueOf(retryable(cause)));
        add(h, LhHeaders.ERROR_STACK, shortStack(cause));
        return h;
    }

    /** Stack abbreviato: classe e messaggio, poi al più {@value #MAX_STACK_FRAMES} frame e la prima causa. */
    public static String shortStack(Throwable cause) {
        StringBuilder sb = new StringBuilder(cause.toString());
        StackTraceElement[] frames = cause.getStackTrace();
        for (int i = 0; i < Math.min(frames.length, MAX_STACK_FRAMES); i++) {
            sb.append("\n\tat ").append(frames[i]);
        }
        if (frames.length > MAX_STACK_FRAMES) {
            sb.append("\n\t... ").append(frames.length - MAX_STACK_FRAMES).append(" altri");
        }
        if (cause.getCause() != null && cause.getCause() != cause) {
            sb.append("\nCaused by: ").append(cause.getCause());
        }
        return truncate(sb.toString(), MAX_STACK_CHARS);
    }

    private static void add(RecordHeaders h, String name, String value) {
        if (value != null) {
            h.add(name, value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
