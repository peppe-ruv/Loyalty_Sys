package it.iren.loyalty.tierservice.domain;

import java.time.Instant;

/**
 * Assegnazione manuale del tier (RF-70), equivalente del "level assigned manually" di Open Loyalty: il customer care o
 * il marketing fissano un tier con causale e scadenza (es. "TOP per 12 mesi come gesto commerciale", "tier ereditato da
 * BeIren"). Finché è valida prevale sul calcolo; alla scadenza si torna al tier qualificato, senza scendere più di un
 * livello (RF-11). Sopra la soglia configurata serve l'approvazione a quattro occhi (RF-18).
 */
public record TierOverride(String memberId, String tierCode, String reason, String actor, Instant from, Instant until) {
    public boolean activeAt(Instant t) { return !t.isBefore(from) && (until == null || t.isBefore(until)); }

    /** Tier effettivo: override attivo se presente, altrimenti quello calcolato. */
    public static TierPolicy.Tier effective(TierPolicy policy, TierPolicy.Tier computed, TierOverride override, Instant at) {
        if (override != null && override.activeAt(at)) return policy.byCode(override.tierCode());
        return computed;
    }

    /** Alla scadenza dell'override si applica la discesa morbida dal tier forzato (RF-11), mai un salto di più livelli. */
    public static TierPolicy.Tier afterExpiry(TierPolicy policy, TierOverride expired, long statusPointsThisYear) {
        return policy.atYearEnd(policy.byCode(expired.tierCode()), statusPointsThisYear);
    }
}
