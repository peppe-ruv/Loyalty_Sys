package io.loyaltyhub.common.kafka;

import io.loyaltyhub.common.event.LhHeaders;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Regole comuni dei messaggi in DLQ (docs/04 §5, docs/05 §1): codice errore, ritentabilità e header
 * {@code lh-*} del record su {@code lh.dlq.v1}. Usate dal recoverer Kafka di {@link LhKafkaConfiguration} e dal
 * bus in-process dell'hub (ADR-024), così i due trasporti producono record DLQ identici.
 */
public final class DlqRecords {

    /** Tentativi totali per un errore ritentabile (1 + 2 ritenti, docs/12 accettazione M0; SPEC-GAP: Q-131). */
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

    /**
     * Errori deterministici: nessun ritentativo li farebbe riuscire (docs/04 §5: «errori non ritentabili (validazione,
     * deserializzazione) diretti in DLQ»). Oltre alle eccezioni di lh-common: JSON illeggibile (Jackson 3 e 2) e gli
     * errori che l'error handler di Spring Kafka non ritenta per default, così i due trasporti (Kafka e bus in-process,
     * ADR-024) decidono allo stesso modo e {@code lh-attempts} dice il vero. Confronto per nome di classe (anche delle
     * superclassi): nessuna dipendenza in più.
     */
    private static final List<String> NON_RETRYABLE_TYPES = List.of(
            "tools.jackson.core.JacksonException",
            "com.fasterxml.jackson.core.JacksonException",
            "org.springframework.kafka.support.serializer.DeserializationException",
            "org.springframework.messaging.converter.MessageConversionException",
            "org.springframework.core.convert.ConversionException",
            "org.springframework.messaging.handler.invocation.MethodArgumentResolutionException",
            "java.lang.NoSuchMethodException",
            "java.lang.ClassCastException",
            "jakarta.validation.ValidationException");

    /** Profondità massima della catena delle cause esaminata (difesa da cicli). */
    private static final int MAX_CAUSES = 10;

    /**
     * Codice errore del record DLQ: {@code LOOP_GUARD}, il codice di un errore non ritentabile, o il nome della classe.
     * SPEC-GAP: Q-334 — la catena delle cause è esaminata: un {@link NonRetryableEventException} avvolto da un'altra
     * eccezione conserva il suo codice.
     */
    public static String errorCode(Throwable cause) {
        for (Throwable t : causes(cause)) {
            if (t instanceof LoopGuardException) {
                return "LOOP_GUARD";
            }
            if (t instanceof NonRetryableEventException nre) {
                return nre.code();
            }
        }
        return cause.getClass().getSimpleName();
    }

    /** Un errore non ritentabile va subito in DLQ (docs/04 §5): nessun ritentativo lo farebbe riuscire. */
    public static boolean retryable(Throwable cause) {
        for (Throwable t : causes(cause)) {
            if (t instanceof NonRetryableEventException || t instanceof LoopGuardException || isA(t, NON_RETRYABLE_TYPES)) {
                return false;
            }
        }
        return true;
    }

    /** Tentativi fatti prima della DLQ con la politica di default di lh-common (Q-131). */
    public static int attemptsFor(Throwable cause) {
        return attemptsFor(cause, MAX_ATTEMPTS);
    }

    /**
     * Tentativi fatti prima della DLQ con {@code maxAttempts} tentativi configurati (ritardi di
     * {@code loyaltyhub.consumer.retry-backoff-ms} + 1): 1 per i non ritentabili.
     */
    public static int attemptsFor(Throwable cause, int maxAttempts) {
        return retryable(cause) ? Math.max(1, maxAttempts) : 1;
    }

    private static List<Throwable> causes(Throwable top) {
        List<Throwable> out = new ArrayList<>();
        Throwable t = top;
        while (t != null && out.size() < MAX_CAUSES && !out.contains(t)) {
            out.add(t);
            t = t.getCause();
        }
        return out;
    }

    private static boolean isA(Throwable t, List<String> typeNames) {
        for (Class<?> c = t.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (typeNames.contains(c.getName())) {
                return true;
            }
        }
        return false;
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
