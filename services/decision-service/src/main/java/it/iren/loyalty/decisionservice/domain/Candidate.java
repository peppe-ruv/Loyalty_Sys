package it.iren.loyalty.decisionservice.domain;

import java.util.Map;

/**
 * Azione ammissibile prodotta dal passo di eligibilità: dalle campagne (effetti del rules-engine), dal catalogo offerte
 * (RF-128) o da una richiesta esplicita. {@code value} e {@code cost} possono sovrascrivere quelli della policy.
 */
public record Candidate(DecisionPolicy.ActionType type, String source, String sourceId, String reference, String wallet, long units,
                        Double value, Double cost, String preferredChannel, Map<String, Object> params) {
    public static Candidate of(DecisionPolicy.ActionType t, String source, String sourceId, String reference) { return new Candidate(t, source, sourceId, reference, null, 0, null, null, null, Map.of()); }
}
