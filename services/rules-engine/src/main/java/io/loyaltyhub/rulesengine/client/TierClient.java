package io.loyaltyhub.rulesengine.client;

/** Porta verso tier-service: tier corrente del membro per i moltiplicatori; assegnazione da effetto di campagna (RF-81). */
public interface TierClient {
    String currentTier(String memberId);
    default void assign(String memberId, String tierCode, String reason) {}
}
