package io.loyaltyhub.engagement.infra;

import io.loyaltyhub.engagement.domain.WebhookUrlPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Invio HTTP di una consegna (docs/servizi/engagement-service.md §5) con il client del JDK: {@code POST} del CloudEvent
 * (modo strutturato, {@code application/cloudevents+json}), header {@code X-LH-Signature}, {@code X-LH-Event-Id},
 * {@code X-LH-Delivery-Id}; timeout 5 s (connessione e risposta); redirect non seguiti. Prima della chiamata l'host è
 * risolto e, se la politica lo chiede, un indirizzo privato/loopback blocca l'invio ({@code BLOCKED_ADDRESS}).
 * Non lancia mai: ogni problema diventa un {@link Result} senza stato HTTP.
 */
// SPEC-GAP: Q-99 — la scheda blocca gli indirizzi privati "nel profilo free", ma l'hub ospitato gira con
// `demo,inproc` (senza free): per prudenza il blocco è attivo in ogni profilo tranne `local`
// (`loyaltyhub.webhooks.block-private-addresses` per forzarlo); `http://localhost` è ammesso solo in `local`
// (`loyaltyhub.webhooks.allow-http-localhost`).
@Component
public class WebhookHttpSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookHttpSender.class);
    public static final Duration TIMEOUT = Duration.ofSeconds(5);
    static final int EXCERPT_MAX = 500;
    private static final int READ_MAX = 4096;

    public record Result(Integer httpStatus, String excerpt, String error, int durationMs) {
    }

    private final HttpClient client;
    private final WebhookUrlPolicy policy;

    public WebhookHttpSender(Environment env) {
        boolean local = env.matchesProfiles("local");
        this.policy = new WebhookUrlPolicy(
                env.getProperty("loyaltyhub.webhooks.allow-http-localhost", Boolean.class, local),
                env.getProperty("loyaltyhub.webhooks.block-private-addresses", Boolean.class, !local));
        this.client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public WebhookUrlPolicy policy() {
        return policy;
    }

    public Result send(String url, String payload, String signature, String eventId, String deliveryId) {
        long start = System.nanoTime();
        Optional<String> invalid = policy.problem(url);
        if (invalid.isPresent()) {
            return new Result(null, "URL non ammesso: " + invalid.get(), "BLOCKED_ADDRESS", 0);
        }
        URI uri = URI.create(url.trim());
        if (policy.blockPrivateAddresses()) {
            try {
                for (InetAddress a : InetAddress.getAllByName(uri.getHost())) {
                    if (WebhookUrlPolicy.isBlocked(a)) {
                        return new Result(null, "Indirizzo privato o locale bloccato: " + a.getHostAddress(), "BLOCKED_ADDRESS",
                                elapsed(start));
                    }
                }
            } catch (UnknownHostException e) {
                return new Result(null, "Host sconosciuto: " + uri.getHost(), "CONNECTION_FAILED", elapsed(start));
            }
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/cloudevents+json; charset=utf-8")
                .header("User-Agent", "LoyaltyHub-Webhook/1")
                .header("X-LH-Signature", signature)
                .header("X-LH-Event-Id", eventId)
                .header("X-LH-Delivery-Id", deliveryId)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            String excerpt;
            try (InputStream body = response.body()) {
                excerpt = excerpt(new String(body.readNBytes(READ_MAX), StandardCharsets.UTF_8));
            }
            return new Result(response.statusCode(), excerpt, null, elapsed(start));
        } catch (HttpTimeoutException e) {
            return new Result(null, "Nessuna risposta entro " + TIMEOUT.toSeconds() + " s", "TIMEOUT", elapsed(start));
        } catch (IOException e) {
            return new Result(null, excerpt(e.getClass().getSimpleName() + ": " + e.getMessage()), "CONNECTION_FAILED",
                    elapsed(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(null, "Invio interrotto", "CONNECTION_FAILED", elapsed(start));
        } catch (RuntimeException e) {
            log.warn("Webhook {}: invio non riuscito", deliveryId, e);
            return new Result(null, excerpt(e.getMessage()), "CONNECTION_FAILED", elapsed(start));
        }
    }

    static String excerpt(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.strip();
        return t.length() <= EXCERPT_MAX ? t : t.substring(0, EXCERPT_MAX) + "…";
    }

    private static int elapsed(long start) {
        return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - start) / 1_000_000);
    }
}
