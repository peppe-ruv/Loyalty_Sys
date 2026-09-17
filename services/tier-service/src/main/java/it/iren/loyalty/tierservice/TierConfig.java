package it.iren.loyalty.tierservice;

import it.iren.loyalty.tierservice.domain.TierSet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Tier set di default (D06); in produzione l'adattatore CMS legge la collezione tier-sets. */
@Configuration
public class TierConfig {
    @Bean @ConditionalOnMissingBean
    TierSet tierSet() { return TierSet.example(); }
}
