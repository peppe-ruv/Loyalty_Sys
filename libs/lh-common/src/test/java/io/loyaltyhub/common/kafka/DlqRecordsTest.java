package io.loyaltyhub.common.kafka;

import io.loyaltyhub.common.event.LhHeaders;
import org.apache.kafka.common.header.Headers;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Regole dei record DLQ (docs/04 §5): codice, ritentabilità, tentativi e header {@code lh-*}. */
class DlqRecordsTest {

    @Test
    void loopGuardAndNonRetryableGoStraightToDlqWithTheirCode() {
        LoopGuardException loop = new LoopGuardException("lhhop 4");
        NonRetryableEventException pool = new NonRetryableEventException("COUPON_POOL_EMPTY", "pool vuoto");
        IllegalStateException boom = new IllegalStateException("boom");

        assertThat(DlqRecords.errorCode(loop)).isEqualTo("LOOP_GUARD");
        assertThat(DlqRecords.errorCode(pool)).isEqualTo("COUPON_POOL_EMPTY");
        assertThat(DlqRecords.errorCode(boom)).isEqualTo("IllegalStateException");

        assertThat(DlqRecords.retryable(loop)).isFalse();
        assertThat(DlqRecords.retryable(pool)).isFalse();
        assertThat(DlqRecords.retryable(boom)).isTrue();
        assertThat(DlqRecords.attemptsFor(pool)).isEqualTo(1);
        assertThat(DlqRecords.attemptsFor(boom)).isEqualTo(4);
    }

    @Test
    void unwrapsTheListenerFailureEnvelope() {
        NonRetryableEventException inner = new NonRetryableEventException("POISON", "avvelenato");
        ListenerExecutionFailedException wrapped = new ListenerExecutionFailedException("listener", "lh-campaign", inner);
        assertThat(DlqRecords.unwrap(wrapped)).isSameAs(inner);
        assertThat(DlqRecords.unwrap(inner)).isSameAs(inner);
    }

    @Test
    void headersCarryOriginConsumerErrorAndAttempts() {
        Headers h = DlqRecords.headers("lh.actions.v1", "lh-campaign",
                new NonRetryableEventException("POISON", "avvelenato"), 1);
        assertThat(value(h, LhHeaders.ERROR_CODE)).isEqualTo("POISON");
        assertThat(value(h, LhHeaders.ORIGINAL_TOPIC)).isEqualTo("lh.actions.v1");
        assertThat(value(h, LhHeaders.CONSUMER)).isEqualTo("lh-campaign");
        assertThat(value(h, LhHeaders.ERROR_CLASS)).isEqualTo(NonRetryableEventException.class.getName());
        assertThat(value(h, LhHeaders.ERROR_MESSAGE)).isEqualTo("avvelenato");
        assertThat(value(h, LhHeaders.ATTEMPTS)).isEqualTo("1");
        assertThat(value(h, LhHeaders.ERROR_RETRYABLE)).isEqualTo("false");
        assertThat(value(h, LhHeaders.ERROR_STACK)).contains("avvelenato").contains("\tat ");
    }

    @Test
    void longMessagesAreTruncated() {
        String longMsg = "x".repeat(5_000);
        Headers h = DlqRecords.headers("t", "c", new IllegalArgumentException(longMsg), 3);
        assertThat(value(h, LhHeaders.ERROR_MESSAGE)).hasSizeLessThan(1_100);
        assertThat(value(h, LhHeaders.ERROR_STACK)).hasSizeLessThanOrEqualTo(4_001);
    }

    private static String value(Headers h, String name) {
        var header = h.lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
