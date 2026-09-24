package io.loyaltyhub.engagement.domain;

import io.loyaltyhub.engagement.domain.WebhookRetry.Status;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Ritenti dei webhook (docs/servizi/engagement-service.md §5): 1, 5, 15 minuti, poi GAVE_UP. */
class WebhookRetryTest {

    private static final Instant T = Instant.parse("2026-09-24T10:00:00Z");

    @Test
    void failuresAreRetriedAfterOneFiveAndFifteenMinutesThenGiveUp() {
        assertThat(WebhookRetry.after(1, false, T)).isEqualTo(new WebhookRetry.Next(Status.FAILED, T.plus(Duration.ofMinutes(1))));
        assertThat(WebhookRetry.after(2, false, T)).isEqualTo(new WebhookRetry.Next(Status.FAILED, T.plus(Duration.ofMinutes(5))));
        assertThat(WebhookRetry.after(3, false, T)).isEqualTo(new WebhookRetry.Next(Status.FAILED, T.plus(Duration.ofMinutes(15))));
        assertThat(WebhookRetry.after(4, false, T)).isEqualTo(new WebhookRetry.Next(Status.GAVE_UP, null));
        assertThat(WebhookRetry.MAX_ATTEMPTS).isEqualTo(4);
    }

    @Test
    void manualRetryAfterGivingUpIsASingleAttempt() {
        assertThat(WebhookRetry.after(5, false, T).status()).isEqualTo(Status.GAVE_UP);
        assertThat(WebhookRetry.after(5, true, T)).isEqualTo(new WebhookRetry.Next(Status.OK, null));
    }

    @Test
    void only2xxIsSuccessAndOnlyFailedOrGaveUpAreRetryable() {
        assertThat(WebhookRetry.isSuccess(200)).isTrue();
        assertThat(WebhookRetry.isSuccess(204)).isTrue();
        assertThat(WebhookRetry.isSuccess(301)).as("i redirect non si seguono").isFalse();
        assertThat(WebhookRetry.isSuccess(500)).isFalse();
        assertThat(WebhookRetry.isSuccess(null)).as("timeout o errore di rete").isFalse();
        assertThat(WebhookRetry.isRetryable("FAILED")).isTrue();
        assertThat(WebhookRetry.isRetryable("GAVE_UP")).isTrue();
        assertThat(WebhookRetry.isRetryable("OK")).isFalse();
        assertThat(WebhookRetry.isRetryable("PENDING")).isFalse();
    }
}
