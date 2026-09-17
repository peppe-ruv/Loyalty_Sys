package it.iren.loyalty.decisionservice.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Decisione strutturata e spiegabile (RF-127, RF-129): azioni scelte con canale e motivo, candidati scartati con codice
 * motivo, punteggi, versione della policy, esperimento e variante, previsioni usate, correlazione con l'evento.
 */
public record Decision(String decisionId, String memberId, String eventId, String eventType, String correlationId, String policyVersion,
                       String experimentId, String variant, List<Chosen> actions, List<Rejected> rejected, Map<String, Double> predictions,
                       String riskLevel, Instant decidedAt, Instant expiresAt) {
    public record Chosen(DecisionPolicy.ActionType action, String reference, String wallet, long units, String channel, double score, List<String> reasons, String source, String sourceId, Map<String, Object> params) {}
    public record Rejected(DecisionPolicy.ActionType action, String reference, String reasonCode, String detail) {}

    public boolean isNoAction() { return actions.isEmpty(); }
    public String primaryAction() { return actions.isEmpty() ? "NO_ACTION" : actions.get(0).action().name(); }
}
