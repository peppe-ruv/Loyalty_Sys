package it.iren.loyalty.decisionservice.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Offerta del catalogo decisionale (RF-128), configurata nel backoffice (collezione {@code offers}): un'azione
 * proponibile (premio, coupon, messaggio, offerta a schermo, campagna) con condizione di eligibilità (SpEL sul contesto),
 * valore, costo, canali e finestra di validità. Diventa un candidato quando la condizione è vera.
 */
public record Offer(String id, String name, boolean active, DecisionPolicy.ActionType action, String reference, String wallet, long units,
                    String conditionExpression, double value, double cost, Set<String> channels, Instant validFrom, Instant validTo,
                    Map<String, Object> params) {

    public boolean isActiveAt(Instant now) {
        if (!active) return false;
        if (validFrom != null && now.isBefore(validFrom)) return false;
        return validTo == null || !now.isAfter(validTo);
    }

    public Candidate toCandidate() {
        String preferred = channels == null || channels.isEmpty() ? null : channels.iterator().next();
        return new Candidate(action, "offer", id, reference == null ? id : reference, wallet, units, value, cost, preferred,
                params == null ? Map.of() : params);
    }
}
