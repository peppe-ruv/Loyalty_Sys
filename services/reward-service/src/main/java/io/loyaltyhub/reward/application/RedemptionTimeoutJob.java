package io.loyaltyhub.reward.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Timeout della saga di richiesta premio (docs/servizi/reward-service.md §5): ogni minuto le richieste {@code PENDING}
 * da oltre 10 minuti diventano {@code REJECTED (TIMEOUT)}. È una rete di sicurezza della saga, non un job "di calendario":
 * resta attivo anche in demo (spegnibile con {@code loyaltyhub.reward.redemption-timeout.enabled=false}).
 */
@Component
@org.springframework.context.annotation.Lazy(false) // docs/06 §5: attivo anche con lazy-initialization (profilo free)
@ConditionalOnProperty(name = "loyaltyhub.reward.redemption-timeout.enabled", havingValue = "true", matchIfMissing = true)
public class RedemptionTimeoutJob {

    private final RedemptionService redemptions;
    private final Clock clock;

    public RedemptionTimeoutJob(RedemptionService redemptions, Clock clock) {
        this.redemptions = redemptions;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${loyaltyhub.reward.redemption-timeout-check-ms:60000}", initialDelay = 30_000)
    public void timeoutPending() {
        redemptions.timeoutPending(clock.instant());
    }
}
