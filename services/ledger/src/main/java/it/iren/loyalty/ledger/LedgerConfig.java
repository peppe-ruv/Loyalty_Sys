package it.iren.loyalty.ledger;

import it.iren.loyalty.ledger.domain.WalletType;
import it.iren.loyalty.ledger.domain.WalletTypeSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;

@Configuration
@EnableScheduling
public class LedgerConfig {
    /** Wallet di default (D05); in produzione l'adattatore CMS aggiunge quelli configurati (RF-87). */
    @Bean
    @ConditionalOnMissingBean
    WalletTypeSource walletTypes() {
        return () -> List.of(WalletType.premio(), WalletType.status(),
                new WalletType("BOLLINI", "Bollini raccolta 2027", "bollino", "bollini", WalletType.Expiration.ANNUAL_DATE, 0, java.time.MonthDay.of(12, 31), 0, false, 0, 0, true, false, true));
    }
}
