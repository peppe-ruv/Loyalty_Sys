package it.iren.loyalty.rulesengine.client;

import it.iren.loyalty.rulesengine.domain.RuleEvaluator;

import java.util.List;

/** Porta verso il ledger. L'implementazione REST/Kafka è sostituibile; nei test è in memoria. */
public interface LedgerClient {
    void post(String memberId, String actionKey, List<RuleEvaluator.Posting> postings);
    void reverse(String originalActionKey);
}
