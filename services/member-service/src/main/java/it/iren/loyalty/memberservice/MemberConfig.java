package it.iren.loyalty.memberservice;

import it.iren.loyalty.memberservice.domain.ReferralPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Politica referral di default; in produzione arriva dalle impostazioni del backoffice (collezione settings). */
@Configuration
public class MemberConfig {
    @Bean
    @ConditionalOnMissingBean
    ReferralPolicy referralPolicy() { return ReferralPolicy.example(); }
}
