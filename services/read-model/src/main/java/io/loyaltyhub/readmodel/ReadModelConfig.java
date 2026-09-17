package io.loyaltyhub.readmodel;

import io.loyaltyhub.common.metrics.LoyaltyMetrics;
import io.loyaltyhub.readmodel.app.ContextStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Cablaggio del read-model: ricalcolo periodico delle finestre mobili del Customer 360 (RF-125). */
@Configuration
@EnableScheduling
public class ReadModelConfig {

    @Bean @ConditionalOnProperty(name = "readmodel.recompute.enabled", havingValue = "true", matchIfMissing = true)
    WindowRecompute windowRecompute(ContextStore store, LoyaltyMetrics metrics, @Value("${readmodel.recompute.page-size:1000}") int pageSize) {
        return new WindowRecompute(store, metrics, pageSize);
    }

    /**
     * Ricalcolo notturno delle finestre: di notte il traffico è basso e un giro completo non disturba le decisioni.
     * L'orario è del programma (Europe/Rome), come tutto ciò che è calendario.
     */
    public static class WindowRecompute {
        private final ContextStore store;
        private final LoyaltyMetrics metrics;
        private final int pageSize;

        WindowRecompute(ContextStore store, LoyaltyMetrics metrics, int pageSize) {
            this.store = store; this.metrics = metrics; this.pageSize = pageSize;
        }

        @Scheduled(cron = "${readmodel.recompute.cron:0 20 3 * * *}", zone = "Europe/Rome")
        public void run() {
            metrics.gauge("loyalty_context_windows_recomputed", store.recomputeWindows(pageSize));
        }
    }
}
