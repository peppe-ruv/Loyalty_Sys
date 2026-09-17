package it.iren.loyalty.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Registra {@link LoyaltyMetrics} in ogni servizio che ha un MeterRegistry (tutti: actuator è nel pom padre). */
@AutoConfiguration
@ConditionalOnBean(MeterRegistry.class)
public class MetricsAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public LoyaltyMetrics loyaltyMetrics(MeterRegistry registry) { return new LoyaltyMetrics(registry); }
}
