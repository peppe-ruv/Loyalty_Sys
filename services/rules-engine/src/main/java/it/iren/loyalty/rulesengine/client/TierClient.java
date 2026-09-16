package it.iren.loyalty.rulesengine.client;

/** Porta verso tier-service: tier corrente del membro per i moltiplicatori. */
public interface TierClient {
    String currentTier(String memberId);
}
