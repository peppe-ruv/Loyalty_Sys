package it.iren.loyalty.decisionservice.app;

import it.iren.loyalty.decisionservice.domain.Decision;

import java.util.Map;

/**
 * Porte di esecuzione degli effetti decisi (RF-127): il decision-service applica le azioni contrattuali sui servizi
 * di dominio (ledger, tier, engagement, member) e consegna le azioni di contatto (messaggi, offerte) al
 * delivery-service tramite l'evento DECISION_V1 — non le esegue mai direttamente.
 */
public interface Effects {
    void awardUnits(String memberId, String actionKey, Decision.Chosen c);
    void grantReward(String memberId, String rewardId, String grantKey);
    void issueCoupon(String memberId, String poolOrRewardId, String grantKey);
    void grantBadge(String memberId, String badgeCode, String grantKey);
    void setAttribute(String memberId, String key, String value);
    void assignTier(String memberId, String tierCode, String reason);
    /** Emette un'azione interna sul topic canonico (es. CAMPAIGN_COMPLETED, EXPERIMENT_EXPOSED, CHURN_RISK_CHANGED). */
    void emitAction(String memberId, String actionType, String idempotencyKey, Map<String, Object> attributes);
    /** Avvia una campagna (automazione) per il membro: in pratica un'azione interna che la campagna ascolta. */
    default void triggerCampaign(String memberId, String campaignId, String key) { emitAction(memberId, "CAMPAIGN_ENTERED", key, Map.of("campaignId", campaignId)); }
}
