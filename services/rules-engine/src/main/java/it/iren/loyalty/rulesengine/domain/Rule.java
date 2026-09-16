package it.iren.loyalty.rulesengine.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Regola punti dichiarativa (RF-05): tipo azione, condizioni sugli attributi, punti nelle due valute,
 * moltiplicatori per tier, tetti e periodo. Versionata e immutabile una volta pubblicata (RF-06).
 * Le regole vengono dal backoffice/CMS tramite {@link RuleSource}; qui non c'è codice per regola.
 */
public record Rule(
        String id,
        String version,
        String actionType,
        List<Condition> conditions,
        long rewardPoints,
        long statusPoints,
        /** moltiplicatore per codice tier, es. {"PLUS": 1.25, "TOP": 1.5}; assente = 1 */
        Map<String, BigDecimal> tierMultipliers,
        /** tetto di punti PREMIO per membro nel periodo (0 = nessun tetto) */
        long capPerMemberPerPeriod,
        Instant validFrom,
        Instant validTo,
        boolean stackable
) {
    public record Condition(String attribute, Operator op, String value) {}
    public enum Operator { EQ, NE, GT, GTE, LT, LTE, IN }

    public boolean isActiveAt(Instant t) {
        return (validFrom == null || !t.isBefore(validFrom)) && (validTo == null || t.isBefore(validTo));
    }

    public BigDecimal multiplierFor(String tierCode) {
        if (tierMultipliers == null || tierCode == null) return BigDecimal.ONE;
        return tierMultipliers.getOrDefault(tierCode, BigDecimal.ONE);
    }
}
