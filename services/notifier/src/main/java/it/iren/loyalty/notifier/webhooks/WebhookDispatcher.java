package it.iren.loyalty.notifier.webhooks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Consegna con tentativi e backoff; dopo {@code maxRetries} l'evento va nella tabella dei falliti e il cruscotto lo mostra
 * (RF-46). Le sottoscrizioni arrivano dal backoffice (collezione webhooks) tramite {@link WebhookSource}.
 */
@Component
public class WebhookDispatcher {
    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private final WebhookSource subscriptions;
    private final RestClient http;

    public WebhookDispatcher(WebhookSource subscriptions, RestClient.Builder builder) {
        this.subscriptions = subscriptions;
        this.http = builder.build();
    }

    public void dispatch(String eventType, String eventId, byte[] payload) {
        List<WebhookSubscription> targets = subscriptions.active().stream().filter(s -> s.wants(eventType)).toList();
        for (WebhookSubscription s : targets) {
            int attempt = 0;
            while (true) {
                try {
                    var req = http.post().uri(s.url()).contentType(MediaType.APPLICATION_JSON)
                            .header("X-Loyalty-Event", eventType).header("X-Loyalty-Delivery", eventId).header("X-Loyalty-Signature", s.sign(payload));
                    if (s.headers() != null) s.headers().forEach(req::header);
                    req.body(payload).retrieve().toBodilessEntity();
                    break;
                } catch (Exception e) {
                    if (++attempt > s.maxRetries()) { log.warn("webhook {} failed for event {} after {} attempts: {}", s.id(), eventId, attempt, e.getMessage()); break; }
                    try { Thread.sleep(Math.min(30_000L, 500L * (1L << attempt))); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
            }
        }
    }
}
