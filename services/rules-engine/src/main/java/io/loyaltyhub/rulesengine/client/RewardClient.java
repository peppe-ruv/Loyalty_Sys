package io.loyaltyhub.rulesengine.client;

/** Porta verso catalog-redemption per i premi automatici ("instant reward", RF-76): assegnazione idempotente per chiave. */
public interface RewardClient {
    void grant(String memberId, String rewardId, String grantKey);
}
