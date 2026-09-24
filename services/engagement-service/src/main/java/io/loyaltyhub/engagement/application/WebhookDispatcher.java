package io.loyaltyhub.engagement.application;

import io.loyaltyhub.engagement.domain.WebhookDelivery;
import io.loyaltyhub.engagement.domain.WebhookRetry;
import io.loyaltyhub.engagement.infra.WebhookDeliveryRepository;
import io.loyaltyhub.engagement.infra.WebhookDeliveryRepository.Attempt;
import io.loyaltyhub.engagement.infra.WebhookDeliveryRepository.Claim;
import io.loyaltyhub.engagement.infra.WebhookHttpSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Invio delle consegne dei webhook (docs/servizi/engagement-service.md §5; F-WBH-01). Mai nel thread del consumer: le
 * consegne nascono come righe {@code PENDING} dal fatto ({@link WebhookService#enqueue}) e partono da qui, chiamato
 * dallo scheduler ogni 30 s ({@link WebhookJobs}), dal job demo ({@code POST /v1/demo/jobs/deliver-webhooks?asOf=}) o
 * da un comando di BO-23 (prova, *Riprova*). Ogni tentativo: presa in carico breve (lease di 2 minuti), chiamata HTTP
 * fuori transazione, esito deciso da {@link WebhookRetry}.
 */
@Service
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    static final int BATCH = 20;
    static final Duration LEASE = Duration.ofMinutes(2);

    public record RunOutcome(int attempted, int ok, int failed, int gaveUp) {
    }

    private final WebhookDeliveryRepository deliveries;
    private final WebhookHttpSender sender;
    private final Clock clock;

    public WebhookDispatcher(WebhookDeliveryRepository deliveries, WebhookHttpSender sender, Clock clock) {
        this.deliveries = deliveries;
        this.sender = sender;
        this.clock = clock;
    }

    /** Consegne dovute a {@code asOf} (al massimo {@value #BATCH} per giro); l'esito e i ritenti si calcolano su {@code asOf}. */
    public RunOutcome deliverDue(Instant asOf) {
        Instant now = clock.instant();
        List<Claim> claims = deliveries.claimDue(asOf, now, now.plus(LEASE), BATCH);
        int ok = 0;
        int failed = 0;
        int gaveUp = 0;
        for (Claim c : claims) {
            switch (attempt(c, asOf)) {
                case OK -> ok++;
                case FAILED -> failed++;
                default -> gaveUp++;
            }
        }
        if (!claims.isEmpty()) {
            log.info("Webhook: {} consegne tentate ({} ok, {} da ritentare, {} abbandonate)", claims.size(), ok, failed, gaveUp);
        }
        return new RunOutcome(claims.size(), ok, failed, gaveUp);
    }

    /**
     * Tentativo immediato di una consegna precisa se è in uno degli stati ammessi e non è già in invio; vuoto
     * altrimenti.
     */
    public Optional<WebhookDelivery> attemptNow(String deliveryId, List<String> statuses) {
        Instant now = clock.instant();
        Optional<Claim> claim = deliveries.claim(deliveryId, statuses, now, now.plus(LEASE));
        claim.ifPresent(c -> attempt(c, now));
        return claim.flatMap(c -> deliveries.find(c.id()));
    }

    private WebhookRetry.Status attempt(Claim c, Instant at) {
        WebhookHttpSender.Result r = sender.send(c.url(), c.payload(), c.signature(), c.eventId(), c.id());
        int attempt = c.attempt() + 1;
        boolean success = WebhookRetry.isSuccess(r.httpStatus());
        WebhookRetry.Next next = WebhookRetry.after(attempt, success, at);
        String error = r.error() != null ? r.error() : success ? null : "HTTP_ERROR";
        deliveries.recordAttempt(c.id(), new Attempt(attempt, next.status().name(), r.httpStatus(), r.excerpt(), error,
                r.durationMs(), next.nextAttemptAt(), at));
        if (!success) {
            log.info("Webhook {} (evento {}): tentativo {} fallito ({}), ora {}", c.id(), c.eventId(), attempt,
                    r.httpStatus() != null ? "HTTP " + r.httpStatus() : error, next.status());
        }
        return next.status();
    }
}
