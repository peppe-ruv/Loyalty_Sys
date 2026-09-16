package it.iren.loyalty.rulesengine;

import it.iren.loyalty.rulesengine.client.LedgerClient;
import it.iren.loyalty.rulesengine.client.TierClient;
import it.iren.loyalty.rulesengine.domain.Rule;
import it.iren.loyalty.rulesengine.domain.RuleEvaluator;
import it.iren.loyalty.rulesengine.domain.RuleSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Adattatori di default. RuleSource in memoria = regole di esempio del seed; in produzione è sostituito
 * dall'adattatore verso il CMS (plugin loyalty) senza toccare il dominio.
 */
@Configuration
public class RulesConfig {

    @Bean
    @ConditionalOnMissingBean
    RuleSource seedRuleSource() {
        List<Rule> seed = List.of(
                new Rule("self-reading", "1", "SELF_READING_SENT", List.of(), 50, 50, Map.of(), 0, null, null, true),
                new Rule("direct-debit", "1", "DIRECT_DEBIT_ACTIVATED", List.of(), 300, 300, Map.of(), 0, null, null, true),
                new Rule("bill-paid", "1", "BILL_PAID_ON_TIME", List.of(new Rule.Condition("amountEur", Rule.Operator.GT, "0")), 20, 20, Map.of(), 0, null, null, true)
        );
        return actionType -> seed.stream().filter(r -> r.actionType().equals(actionType)).toList();
    }

    @Bean
    @ConditionalOnMissingBean
    LedgerClient ledgerClient(RestClient.Builder builder) {
        RestClient client = builder.baseUrl(System.getenv().getOrDefault("LEDGER_URL", "http://ledger:8083")).build();
        return new LedgerClient() {
            @Override public void post(String memberId, String actionKey, List<RuleEvaluator.Posting> postings) {
                for (var p : postings) {
                    client.post().uri("/v1/ledger/postings").body(Map.of(
                            "memberId", memberId, "actionKey", actionKey, "currency", p.currency().name(),
                            "amount", p.amount(), "reason", "RULE:" + p.ruleId() + "@" + p.ruleVersion())).retrieve().toBodilessEntity();
                }
            }
            @Override public void reverse(String originalActionKey) {
                client.post().uri("/v1/ledger/reversals/{k}", originalActionKey).retrieve().toBodilessEntity();
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    TierClient tierClient(RestClient.Builder builder) {
        RestClient client = builder.baseUrl(System.getenv().getOrDefault("TIER_URL", "http://tier-service:8084")).build();
        return memberId -> {
            try {
                var body = client.get().uri("/v1/tiers/members/{id}", memberId).retrieve().body(Map.class);
                return body == null ? "BASE" : String.valueOf(body.getOrDefault("tier", "BASE"));
            } catch (Exception e) { return "BASE"; }
        };
    }
}
