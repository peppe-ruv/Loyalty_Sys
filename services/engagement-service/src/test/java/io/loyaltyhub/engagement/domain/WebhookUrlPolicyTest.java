package io.loyaltyhub.engagement.domain;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

/** URL ammessi per i webhook (docs/servizi/engagement-service.md §5, anti-SSRF). */
class WebhookUrlPolicyTest {

    private static final WebhookUrlPolicy HOSTED = new WebhookUrlPolicy(false, true);
    private static final WebhookUrlPolicy LOCAL = new WebhookUrlPolicy(true, false);

    @Test
    void onlyHttpsOutsideLocal() {
        assertThat(HOSTED.problem("https://example.org/hook")).isEmpty();
        assertThat(HOSTED.problem("http://example.org/hook")).contains("solo https://");
        assertThat(HOSTED.problem("ftp://example.org/hook")).contains("solo https://");
        assertThat(HOSTED.problem("http://localhost:4000/hook")).isPresent();
        assertThat(HOSTED.problem("")).contains("obbligatorio");
        assertThat(HOSTED.problem("example.org/hook")).isPresent();
        assertThat(HOSTED.problem("https://utente:pw@example.org/hook")).isPresent();
        assertThat(HOSTED.problem("https://example.org/" + "a".repeat(600))).isPresent();
    }

    @Test
    void localAllowsHttpOnlyTowardsLocalhost() {
        assertThat(LOCAL.problem("http://localhost:4000/hook")).isEmpty();
        assertThat(LOCAL.problem("http://127.0.0.1:4000/hook")).isEmpty();
        assertThat(LOCAL.problem("http://example.org/hook")).isPresent();
        assertThat(LOCAL.problem("https://localhost/hook")).isEmpty();
    }

    @Test
    void privateAndLoopbackLiteralsAreBlockedWhenHosted() {
        assertThat(HOSTED.problem("https://localhost/hook")).isPresent();
        assertThat(HOSTED.problem("https://127.0.0.1/hook")).isPresent();
        assertThat(HOSTED.problem("https://10.1.2.3/hook")).isPresent();
        assertThat(HOSTED.problem("https://192.168.1.10/hook")).isPresent();
        assertThat(HOSTED.problem("https://169.254.169.254/latest/meta-data")).isPresent();
        assertThat(HOSTED.problem("https://[::1]/hook")).isPresent();
        assertThat(HOSTED.problem("https://servizio.internal/hook")).isPresent();
        assertThat(HOSTED.problem("https://93.184.215.14/hook")).isEmpty();
    }

    @Test
    void blockedAddressRanges() throws Exception {
        for (String ip : new String[]{"127.0.0.1", "10.0.0.1", "172.16.5.4", "192.168.0.1", "169.254.1.1", "100.64.0.1",
                "0.0.0.0", "::1", "fd00::1", "fe80::1", "224.0.0.1"}) {
            assertThat(WebhookUrlPolicy.isBlocked(InetAddress.getByName(ip))).as(ip).isTrue();
        }
        for (String ip : new String[]{"93.184.215.14", "8.8.8.8", "2606:4700::1111"}) {
            assertThat(WebhookUrlPolicy.isBlocked(InetAddress.getByName(ip))).as(ip).isFalse();
        }
    }
}
