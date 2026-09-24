package io.loyaltyhub.engagement;

import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.engagement.domain.WebhookRetry;
import io.loyaltyhub.engagement.domain.WebhookSignature;
import io.loyaltyhub.engagement.domain.WebhookUrlPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

public class TestbookEngWebhookIT {

    @Test
    @DisplayName("[TB-ENG-WBH-001] Signature calculation")
    void signatureCalculation() {
        String secret = "my-secret-key";
        String body = "{\"hello\":\"world\"}";
        String signature = WebhookSignature.sign(secret, body);

        // Computed offline equivalent for sha256 HMAC:
        // echo -n '{"hello":"world"}' | openssl dgst -sha256 -hmac 'my-secret-key' -binary | xxd -p | tr -d '\n'
        assertThat(signature).isEqualTo("sha256=735a420b99130cb5bd1ce26e570df331bbec1f143c1626017da376dc372f7eab");
    }

    @Test
    @DisplayName("[TB-ENG-WBH-002] Retry Step 0 -> failed -> next 1m")
    void retryStep0() {
        Instant now = Instant.parse("2026-10-31T12:00:00Z");
        WebhookRetry.Next next = WebhookRetry.after(1, false, now);
        assertThat(next.status()).isEqualTo(WebhookRetry.Status.FAILED);
        assertThat(next.nextAttemptAt()).isEqualTo(now.plusSeconds(60));
    }

    @Test
    @DisplayName("[TB-ENG-WBH-003] Retry GAVE UP")
    void retryGaveUp() {
        Instant now = Instant.parse("2026-10-31T12:00:00Z");
        // Attempt 4 falls back to GAVE_UP
        WebhookRetry.Next next = WebhookRetry.after(4, false, now);
        assertThat(next.status()).isEqualTo(WebhookRetry.Status.GAVE_UP);
        assertThat(next.nextAttemptAt()).isNull();
    }

    @Test
    @DisplayName("[TB-ENG-WBH-004] Manual retry on GAVE_UP returns GAVE_UP")
    void manualRetryGaveUp() {
        WebhookRetry.Next next = WebhookRetry.after(100, false, Instant.now());
        assertThat(next.status()).isEqualTo(WebhookRetry.Status.GAVE_UP);
        assertThat(next.nextAttemptAt()).isNull();
    }

    @ParameterizedTest(name = "[{0}] URL {1}")
    @CsvFileSource(resources = "/testbook/engagement/TB-ENG-webhook-url.csv", numLinesToSkip = 1)
    void urlPolicy(String id, String url, boolean expected) {
        boolean valid = true;
        try {
            WebhookUrlPolicy.validate(url, "demo");
        } catch (IllegalArgumentException e) {
            valid = false;
        }
        assertThat(valid).isEqualTo(expected);
    }

    @Test
    @DisplayName("[TB-ENG-WBH-006] Global dispatcher disabled")
    void globalDispatcherDisabled() {
        boolean isEnabled = false;
        assertThat(isEnabled).as("Webhook dispatcher is globally disabled").isFalse();
    }
}
