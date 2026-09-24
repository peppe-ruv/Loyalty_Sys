package io.loyaltyhub.engagement.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduler delle consegne dei webhook (docs/servizi/engagement-service.md §5): ogni 30 s invia le consegne dovute.
 * Come il timeout delle richieste premio (Q-55) è una parte del flusso, non un job "di calendario": resta attivo anche
 * in demo, dove i job schedulati sono spenti ({@code loyaltyhub.jobs.enabled=false}); si spegne con
 * {@code loyaltyhub.webhooks.dispatcher.enabled=false}. Costo trascurabile: una query indicizzata ogni 30 s (l'outbox
 * interroga già il DB ogni 500 ms). Le chiamate HTTP (fino a 5 s l'una) girano su un thread virtuale dedicato, un giro
 * alla volta: il thread dello scheduler, condiviso con il relay dell'outbox, non resta mai bloccato.
 */
// SPEC-GAP: Q-A5 — "scheduler ogni 30 s" con i job spenti in demo (ADR-024): senza questo interruttore dedicato le
// consegne resterebbero PENDING nella demo ospitata. In più `POST /v1/demo/jobs/deliver-webhooks?asOf=` (BO-30).
@Component
@Lazy(false)
@ConditionalOnProperty(name = "loyaltyhub.webhooks.dispatcher.enabled", havingValue = "true", matchIfMissing = true)
public class WebhookJobs {

    private static final Logger log = LoggerFactory.getLogger(WebhookJobs.class);

    private final WebhookDispatcher dispatcher;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public WebhookJobs(WebhookDispatcher dispatcher, Clock clock) {
        this.dispatcher = dispatcher;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${loyaltyhub.webhooks.dispatcher.interval-ms:30000}", initialDelay = 30_000)
    public void deliverDue() {
        if (!running.compareAndSet(false, true)) {
            return; // il giro precedente sta ancora inviando
        }
        Thread.ofVirtual().name("lh-webhooks").start(() -> {
            try {
                dispatcher.deliverDue(clock.instant());
            } catch (RuntimeException e) {
                log.warn("Giro dei webhook non riuscito: si riprova al prossimo", e);
            } finally {
                running.set(false);
            }
        });
    }
}
