package it.iren.loyalty.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registra {@link LoyaltyMetrics} in ogni servizio che ha un MeterRegistry (tutti: actuator è nel pom padre).
 *
 * <p>L'ordine non è un dettaglio: {@code @ConditionalOnBean} guarda i bean già definiti quando la condizione
 * viene valutata, quindi senza {@code afterName} questa auto-configurazione può girare prima che Micrometer
 * abbia registrato il registro — la condizione risulta falsa, il bean non nasce e ogni servizio che inietta
 * {@code LoyaltyMetrics} non si avvia.
 */
@AutoConfiguration(afterName = "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration")
@ConditionalOnBean(MeterRegistry.class)
public class MetricsAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public LoyaltyMetrics loyaltyMetrics(MeterRegistry registry) { return new LoyaltyMetrics(registry); }
}
