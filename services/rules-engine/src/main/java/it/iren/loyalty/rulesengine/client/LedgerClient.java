package it.iren.loyalty.rulesengine.client;

import it.iren.loyalty.rulesengine.campaign.CampaignEvaluator;
import it.iren.loyalty.rulesengine.domain.RuleEvaluator;

import java.util.List;
import java.util.Map;

/** Porta verso il ledger. L'implementazione REST/Kafka è sostituibile; nei test è in memoria. */
public interface LedgerClient {
    void post(String memberId, String actionKey, List<RuleEvaluator.Posting> postings);
    void reverse(String originalActionKey);
    /** Vista wallet del membro per le espressioni (RF-84) e per i limiti. */
    default Map<String, CampaignEvaluator.WalletView> wallets(String memberId) { return Map.of(); }
    /** Contatori d'uso per campagna (RF-82): esecuzioni e unità nel periodo, unità totali della campagna. */
    default Map<String, CampaignEvaluator.Usage> usage(String memberId, String actionType) { return Map.of(); }
}
