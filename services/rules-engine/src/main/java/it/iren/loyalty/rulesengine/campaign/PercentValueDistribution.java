package it.iren.loyalty.rulesengine.campaign;

import java.math.BigDecimal;
import java.util.List;

/**
 * Distribuzione percentuale per fasce cumulative (RF-86), per cashback a scaglioni: dato il valore già accumulato
 * ({@code before}) e il nuovo progresso, applica a ogni tratto la percentuale della fascia in cui cade.
 * Esempio: soglie [5000, 7500, 10000], percentuali [0.01, 0.012, 0.015, 0] → 10.000 € spesi in un mese = 117,5 unità.
 */
public final class PercentValueDistribution {
    private PercentValueDistribution() {}

    public static BigDecimal apply(BigDecimal progress, List<BigDecimal> thresholds, List<BigDecimal> percents, BigDecimal before) {
        if (percents.size() != thresholds.size() + 1) throw new IllegalArgumentException("percents must be thresholds + 1");
        BigDecimal from = before, to = before.add(progress), total = BigDecimal.ZERO;
        BigDecimal lower = BigDecimal.ZERO;
        for (int i = 0; i <= thresholds.size(); i++) {
            BigDecimal upper = i < thresholds.size() ? thresholds.get(i) : null;
            BigDecimal segFrom = from.max(lower);
            BigDecimal segTo = upper == null ? to : to.min(upper);
            if (segTo.compareTo(segFrom) > 0) total = total.add(segTo.subtract(segFrom).multiply(percents.get(i)));
            if (upper == null) break;
            lower = upper;
        }
        return total;
    }
}
