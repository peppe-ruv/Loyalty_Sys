package it.iren.loyalty.catalogredemption;

import it.iren.loyalty.catalogredemption.domain.LedgerPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** Adattatori del servizio catalogo/riscatti: sostituibili nei test (CLAUDE.md §7). */
@Configuration
public class CatalogConfig {

    @Bean
    @ConditionalOnMissingBean
    LedgerPort ledgerPort(RestClient.Builder builder) {
        RestClient ledger = builder.baseUrl(System.getenv().getOrDefault("LEDGER_URL", "http://ledger:8083")).build();
        return new LedgerPort() {
            @Override public void debit(String memberId, String actionKey, long units, String reason) {
                try {
                    ledger.post().uri("/v1/ledger/debits")
                            .body(Map.of("memberId", memberId, "actionKey", actionKey, "points", units, "reason", reason))
                            .retrieve().toBodilessEntity();
                } catch (HttpClientErrorException.Conflict e) {
                    throw new InsufficientBalance("saldo insufficiente per " + actionKey);
                }
            }

            @Override public void reverse(String actionKey) {
                ledger.post().uri("/v1/ledger/reversals/{k}", actionKey).retrieve().toBodilessEntity();
            }
        };
    }
}
