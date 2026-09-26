package io.loyaltyhub.common.testbook;

import io.loyaltyhub.common.event.LhHeaders;
import io.loyaltyhub.common.kafka.DlqRecords;
import io.loyaltyhub.common.kafka.LoopGuardException;
import io.loyaltyhub.common.kafka.NonRetryableEventException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.converter.MessageConversionException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static io.loyaltyhub.common.testbook.TestbookPltSupport.outcome;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-PLT §DLR — regole comuni dei record DLQ ({@link DlqRecords}, docs/04 §5, docs/05 §1, §7): classificazione
 * ritentabile / non ritentabile per tipo d'errore, codice {@code lh-error-code}, numero di tentativi e forma degli
 * header {@code lh-*}. Le stesse regole valgono per il recoverer Kafka e per il bus in-process dell'hub (ADR-024).
 */
class TestbookPltDlqTest {

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/plt/dlq-rules.csv", numLinesToSkip = 1)
    void rules(String id, String description, String error, String field, String expected) {
        assertThat(outcome(() -> field(error(error), field))).as("%s: %s", id, description).isEqualTo(expected);
    }

    private static Object field(Throwable raw, String field) {
        Throwable cause = DlqRecords.unwrap(raw);
        return switch (field) {
            case "code" -> DlqRecords.errorCode(cause);
            case "retryable" -> DlqRecords.retryable(cause);
            case "attempts" -> DlqRecords.attemptsFor(cause);
            case "attempts4" -> DlqRecords.attemptsFor(cause, 4);
            case "headerNames" -> {
                List<String> names = new ArrayList<>();
                for (Header h : DlqRecords.headers("lh.facts.v1", "lh-wallet", cause, 3)) {
                    names.add(h.key());
                }
                yield String.join(" ", names);
            }
            case "message" -> header(cause, LhHeaders.ERROR_MESSAGE);
            case "messageLength" -> header(cause, LhHeaders.ERROR_MESSAGE).length();
            case "class" -> header(cause, LhHeaders.ERROR_CLASS);
            case "originalTopic" -> header(cause, LhHeaders.ORIGINAL_TOPIC);
            case "consumer" -> header(cause, LhHeaders.CONSUMER);
            case "attemptsHeader" -> header(cause, LhHeaders.ATTEMPTS);
            case "retryableHeader" -> header(cause, LhHeaders.ERROR_RETRYABLE);
            case "stackFrames" -> header(cause, LhHeaders.ERROR_STACK).lines().filter(l -> l.startsWith("\tat ")).count();
            case "stackTail" -> header(cause, LhHeaders.ERROR_STACK).lines().filter(l -> l.startsWith("\t... ")).map(String::trim).findFirst()
                    .orElse("-");
            case "stackCause" -> header(cause, LhHeaders.ERROR_STACK).lines().filter(l -> l.startsWith("Caused by:"))
                    .findFirst().orElse("-");
            case "stackMax" -> header(cause, LhHeaders.ERROR_STACK).length() <= 4_001;
            default -> throw new IllegalArgumentException(field);
        };
    }

    private static String header(Throwable cause, String name) {
        Headers headers = DlqRecords.headers("lh.facts.v1", "lh-wallet", cause, 3);
        Header h = headers.lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    /** Errori tipici dei consumer, per nome di caso. */
    private static Throwable error(String kind) {
        return switch (kind) {
            case "state" -> new IllegalStateException("guasto transitorio");
            case "nonRetryable" -> new NonRetryableEventException("COUPON_POOL_EMPTY", "Pool coupon esaurito");
            case "loopGuard" -> new LoopGuardException("Catena interna troppo profonda (lhhop 3)");
            case "json" -> parseError();
            case "deserialization" -> new DeserializationException("valore illeggibile", new byte[]{1}, false, null);
            case "conversion" -> new MessageConversionException("conversione impossibile");
            case "classCast" -> new ClassCastException("tipo inatteso");
            case "conversionFailed" -> new ConversionFailedException(TypeDescriptor.valueOf(String.class),
                    TypeDescriptor.valueOf(Integer.class), "x", new NumberFormatException("x"));
            case "dbDown" -> new DataAccessResourceFailureException("Connessione al database rifiutata");
            case "wrappedListener" -> new ListenerExecutionFailedException("listener",
                    new NonRetryableEventException("INVALID_EFFECT", "effetto senza membro"));
            case "wrappedTwice" -> new ListenerExecutionFailedException("esterno",
                    new ListenerExecutionFailedException("interno", new IllegalStateException("boom")));
            case "causeNonRetryable" -> new IllegalStateException("involucro",
                    new NonRetryableEventException("TEMPLATE_NOT_FOUND", "template assente"));
            case "causeLoopGuard" -> new RuntimeException("involucro", new LoopGuardException("hop 4"));
            case "causeJson" -> new IllegalArgumentException("involucro", parseError());
            case "nullMessage" -> new IllegalStateException((String) null);
            case "message1000" -> new IllegalStateException("m".repeat(1000));
            case "message1001" -> new IllegalStateException("m".repeat(1001));
            case "deepStack" -> deep(40);
            case "shallowStack" -> deep(0);
            case "withCause" -> new IllegalStateException("esterno", new IllegalArgumentException("interno"));
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private static Throwable parseError() {
        try {
            new ObjectMapper().readTree("{non json");
            throw new AssertionError("atteso un errore di parsing");
        } catch (tools.jackson.core.JacksonException e) {
            return e;
        }
    }

    private static Throwable deep(int depth) {
        if (depth == 0) {
            IllegalStateException e = new IllegalStateException("profondo");
            e.setStackTrace(new StackTraceElement[]{new StackTraceElement("io.loyaltyhub.X", "m", "X.java", 1)});
            return e;
        }
        IllegalStateException e = new IllegalStateException("profondo");
        StackTraceElement[] frames = new StackTraceElement[depth];
        for (int i = 0; i < depth; i++) {
            frames[i] = new StackTraceElement("io.loyaltyhub.X" + i, "metodo" + i, "X.java", i + 1);
        }
        e.setStackTrace(frames);
        return e;
    }
}
