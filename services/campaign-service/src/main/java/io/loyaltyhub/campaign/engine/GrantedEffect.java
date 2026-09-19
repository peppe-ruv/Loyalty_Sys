package io.loyaltyhub.campaign.engine;

/**
 * Effetto {@code points.grant} deciso dal motore (docs/05 §4, EVT-EFF-01). Il moltiplicatore di tier
 * <em>non</em> è applicato qui: {@code amount = floor(baseAmount × campaignMultiplier)}; il wallet applica
 * poi il moltiplicatore di livello se {@code tierMultiplierApplies} (docs/03 §3.4, docs/10 §7).
 */
public record GrantedEffect(
        String effectId,
        String campaignCode,
        String currency,
        long baseAmount,
        double campaignMultiplier,
        long amount,
        boolean tierMultiplierApplies,
        int pendingDays,
        String description
) {
}
