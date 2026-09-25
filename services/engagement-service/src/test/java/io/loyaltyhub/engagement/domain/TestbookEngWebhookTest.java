package io.loyaltyhub.engagement.domain;

import io.loyaltyhub.engagement.TestbookRows;
import com.sun.net.httpserver.HttpServer;
import io.loyaltyhub.engagement.infra.WebhookHttpSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.aggregator.ArgumentsAccessor;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-ENG (docs/testbook/TB-ENG-engagement.md): webhook in uscita, logica pura e invio HTTP senza Spring.
 * Righe WURL (URL ammessi e indirizzi privati, {@link WebhookUrlPolicy}), WRTY (tabella dei ritenti,
 * {@link WebhookRetry}), WSIG (firma HMAC, {@link WebhookSignature}), WPRF (politica per profilo e controlli all'invio,
 * {@link WebhookHttpSender}).
 */
class TestbookEngWebhookTest {

    /** Profili diversi da {@code local} (Q-99): solo https, indirizzi privati bloccati. */
    private static final WebhookUrlPolicy REMOTE = new WebhookUrlPolicy(false, true);
    /** Profilo {@code local}: anche http://localhost, nessun blocco. */
    private static final WebhookUrlPolicy LOCAL = new WebhookUrlPolicy(true, false);

    // ---------------------------------------------------------------- WURL

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/engagement/wurl.csv", numLinesToSkip = 1, delimiter = '\t', quoteCharacter = '~',
            maxCharsPerColumn = 8192)
    void urlPolicy(ArgumentsAccessor row) throws Exception {
        String[] c = TestbookRows.columns(row);
        urlPolicy(c[0], c[1], c[2], c[3], c[4]);
    }

    private void urlPolicy(String id, String description, String policy, String url, String expected) {
        // TESTBOOK: ambiguo, vedi le righe WURL marcate AMBIGUO (intervalli speciali oltre privati/loopback, nomi .local e
        // .internal, forma decimale, credenziali, frammento, lunghezza massima).
        WebhookUrlPolicy p = "LOCAL".equals(policy) ? LOCAL : REMOTE;
        String u = "<empty>".equals(url) ? "" : url;
        assertThat(p.problem(u).isEmpty()).as(p.problem(u).orElse("ammesso")).isEqualTo("OK".equals(expected));
    }

    // ---------------------------------------------------------------- WRTY

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/engagement/wrty.csv", numLinesToSkip = 1, delimiter = '\t', quoteCharacter = '~',
            maxCharsPerColumn = 8192)
    void retryPolicy(ArgumentsAccessor row) throws Exception {
        String[] c = TestbookRows.columns(row);
        retryPolicy(c[0], c[1], c[2], c[3], c[4]);
    }

    private void retryPolicy(String id, String description, String op, String input, String expected) {
        // TESTBOOK: ambiguo, vedi TB-ENG-WRTY-009 (tentativo 0) e TB-ENG-WRTY-020 (Riprova su PENDING).
        Instant now = Instant.parse("2026-09-24T10:00:00Z");
        switch (op) {
            case "AFTER" -> {
                String[] parts = input.split(":");
                WebhookRetry.Next next = WebhookRetry.after(Integer.parseInt(parts[0]), Boolean.parseBoolean(parts[1]), now);
                if (expected.startsWith("FAILED+")) {
                    long minutes = Long.parseLong(expected.substring("FAILED+".length()));
                    assertThat(next.status()).isEqualTo(WebhookRetry.Status.FAILED);
                    assertThat(next.nextAttemptAt()).isEqualTo(now.plus(Duration.ofMinutes(minutes)));
                } else {
                    assertThat(next.status()).isEqualTo(WebhookRetry.Status.valueOf(expected));
                    assertThat(next.nextAttemptAt()).isNull();
                }
            }
            case "MAX" -> assertThat(WebhookRetry.MAX_ATTEMPTS).isEqualTo(Integer.parseInt(expected));
            case "SUCCESS" -> assertThat(WebhookRetry.isSuccess("<null>".equals(input) ? null : Integer.valueOf(input)))
                    .isEqualTo(Boolean.parseBoolean(expected));
            case "RETRYABLE" -> assertThat(WebhookRetry.isRetryable(input)).isEqualTo(Boolean.parseBoolean(expected));
            default -> throw new IllegalArgumentException(op);
        }
    }

    // ---------------------------------------------------------------- WSIG

    private static final String FOX = "The quick brown fox jumps over the lazy dog";

    @Test
    @DisplayName("[TB-ENG-WSIG-001] firma di un vettore noto HMAC-SHA256")
    void knownVector() {
        assertThat(WebhookSignature.sign("key", FOX))
                .isEqualTo("sha256=f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    @Test
    @DisplayName("[TB-ENG-WSIG-002] firma calcolata sui byte UTF-8 del corpo")
    void utf8Body() {
        assertThat(WebhookSignature.sign("whsec_test", "città €"))
                .isEqualTo("sha256=f80ce4c01c82e082e5971f31d5e8e4207d4fe789364ec8de8901c50bd4989011");
        assertThat(WebhookSignature.sign("whsec_test", "città €".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(WebhookSignature.sign("whsec_test", "città €"));
    }

    @Test
    @DisplayName("[TB-ENG-WSIG-003] verifica con il segreto giusto")
    void verifyOk() {
        String sig = WebhookSignature.sign("whsec_a", FOX);
        assertThat(WebhookSignature.verify("whsec_a", FOX.getBytes(StandardCharsets.UTF_8), sig)).isTrue();
    }

    @Test
    @DisplayName("[TB-ENG-WSIG-004] verifica con un altro segreto")
    void verifyWrongSecret() {
        String sig = WebhookSignature.sign("whsec_a", FOX);
        assertThat(WebhookSignature.verify("whsec_b", FOX.getBytes(StandardCharsets.UTF_8), sig)).isFalse();
    }

    @Test
    @DisplayName("[TB-ENG-WSIG-005] verifica con il corpo alterato")
    void verifyTamperedBody() {
        String sig = WebhookSignature.sign("whsec_a", FOX);
        assertThat(WebhookSignature.verify("whsec_a", (FOX + ".").getBytes(StandardCharsets.UTF_8), sig)).isFalse();
    }

    @Test
    @DisplayName("[TB-ENG-WSIG-006] verifica senza firma")
    void verifyNull() {
        assertThat(WebhookSignature.verify("whsec_a", FOX.getBytes(StandardCharsets.UTF_8), null)).isFalse();
    }

    @Test
    @DisplayName("[TB-ENG-WSIG-007] verifica con la firma senza prefisso sha256=")
    void verifyNoPrefix() {
        String sig = WebhookSignature.sign("whsec_a", FOX).substring(WebhookSignature.PREFIX.length());
        assertThat(WebhookSignature.verify("whsec_a", FOX.getBytes(StandardCharsets.UTF_8), sig)).isFalse();
    }

    // ---------------------------------------------------------------- WPRF

    @Test
    @DisplayName("[TB-ENG-WPRF-001] nessun profilo attivo: privati bloccati, http://localhost non ammesso")
    void defaultProfileBlocks() {
        WebhookUrlPolicy p = sender(new MockEnvironment()).policy();
        assertThat(p.blockPrivateAddresses()).isTrue();
        assertThat(p.allowHttpLocalhost()).isFalse();
    }

    @Test
    @DisplayName("[TB-ENG-WPRF-002] profilo demo: privati bloccati")
    void demoProfileBlocks() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("demo");
        assertThat(sender(env).policy().blockPrivateAddresses()).isTrue();
        assertThat(sender(env).policy().problem("https://192.168.1.10/hook")).isPresent();
    }

    @Test
    @DisplayName("[TB-ENG-WPRF-003] profilo free: privati bloccati")
    void freeProfileBlocks() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("demo", "free");
        assertThat(sender(env).policy().blockPrivateAddresses()).isTrue();
        assertThat(sender(env).policy().problem("https://10.1.2.3/hook")).isPresent();
    }

    @Test
    @DisplayName("[TB-ENG-WPRF-004] profilo local: privati ammessi, http://localhost ammesso")
    void localProfileAllows() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("demo", "local");
        WebhookUrlPolicy p = sender(env).policy();
        assertThat(p.blockPrivateAddresses()).isFalse();
        assertThat(p.allowHttpLocalhost()).isTrue();
        assertThat(p.problem("http://localhost:9000/hook")).isEmpty();
    }

    @Test
    @DisplayName("[TB-ENG-WPRF-005] profilo local con block-private-addresses=true: privati bloccati")
    void localProfileOverride() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");
        env.setProperty("loyaltyhub.webhooks.block-private-addresses", "true");
        assertThat(sender(env).policy().blockPrivateAddresses()).isTrue();
    }

    @Test
    @DisplayName("[TB-ENG-WPRF-006] invio verso https://127.0.0.1 con blocco: BLOCKED_ADDRESS senza chiamata")
    void sendBlockedLiteral() {
        WebhookHttpSender.Result r = sender(new MockEnvironment()).send("https://127.0.0.1:1/hook", "{}", "sha256=x", "E", "D");
        assertThat(r.httpStatus()).isNull();
        assertThat(r.error()).isEqualTo("BLOCKED_ADDRESS");
    }

    @Test
    @DisplayName("[TB-ENG-WPRF-007] invio verso un nome risolto in un indirizzo locale/privato: BLOCKED_ADDRESS")
    void sendBlockedAfterDns() throws Exception {
        // Il nome della macchina si risolve senza DNS esterno (hosts) in loopback o in un indirizzo privato.
        String host = InetAddress.getLocalHost().getHostName();
        WebhookHttpSender.Result r = sender(new MockEnvironment()).send("https://" + host + ":1/hook", "{}", "sha256=x", "E", "D");
        assertThat(r.httpStatus()).isNull();
        assertThat(r.error()).isEqualTo("BLOCKED_ADDRESS");
    }

    @Test
    @DisplayName("[TB-ENG-WPRF-008] endpoint che risponde dopo 6 s: TIMEOUT a 5 s")
    void timeoutFiveSeconds() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(6_500);
                exchange.sendResponseHeaders(204, -1);
            } catch (InterruptedException | java.io.IOException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        try {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("local");
            long start = System.nanoTime();
            WebhookHttpSender.Result r = sender(env).send("http://localhost:" + server.getAddress().getPort() + "/slow", "{}",
                    "sha256=x", "E", "D");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(r.error()).isEqualTo("TIMEOUT");
            assertThat(r.httpStatus()).isNull();
            assertThat(elapsedMs).isBetween(4_500L, 6_400L);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("[TB-ENG-WPRF-009] endpoint che risponde 302: stato registrato, redirect non seguito")
    void redirectNotFollowed() throws Exception {
        AtomicInteger followed = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/moved", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            followed.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("local");
            WebhookHttpSender.Result r = sender(env).send("http://localhost:" + server.getAddress().getPort() + "/moved", "{}",
                    "sha256=x", "E", "D");
            assertThat(r.httpStatus()).isEqualTo(302);
            assertThat(WebhookRetry.isSuccess(r.httpStatus())).isFalse();
            assertThat(followed.get()).isZero();
        } finally {
            server.stop(0);
        }
    }

    private static WebhookHttpSender sender(MockEnvironment env) {
        return new WebhookHttpSender(env);
    }
}
