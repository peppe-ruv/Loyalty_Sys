package io.loyaltyhub.catalogredemption.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Buono a conversione di unità (RF-102): il membro sceglie quante unità convertire; il valore del buono è
 * {@code unità × tasso}, tra un minimo e un massimo e a passi fissi (es. 100 punti = 1 €, da 500 a 5.000 punti a passi di 100).
 * Usato anche per "paga con i punti" (RF-104): l'importo del carrello determina le unità da scalare.
 */
public record UnitsConversion(BigDecimal eurPerUnit, long minUnits, long maxUnits, long stepUnits) {
    public BigDecimal valueOf(long units) {
        if (units < minUnits || (maxUnits > 0 && units > maxUnits) || (stepUnits > 0 && (units - minUnits) % stepUnits != 0)) throw new IllegalArgumentException("units out of range/step");
        return eurPerUnit.multiply(BigDecimal.valueOf(units)).setScale(2, RoundingMode.DOWN);
    }
    /**
     * Unità da scalare per coprire un importo, <b>senza mai superarlo</b>: si arrotonda per difetto al
     * passo e il resto del carrello si paga normalmente. Arrotondare per eccesso faceva scontare più
     * dell'importo — 5,55 € diventavano 600 punti, cioè 6,00 € di sconto (RF-104).
     *
     * @return 0 se l'importo non copre nemmeno il taglio minimo: in quel caso non si paga con i punti.
     */
    public long unitsFor(BigDecimal eur) {
        long raw = eur.divide(eurPerUnit, 0, RoundingMode.FLOOR).longValue();
        if (raw < minUnits) return 0;
        if (stepUnits > 0) raw = minUnits + (long) Math.floor((raw - minUnits) / (double) stepUnits) * stepUnits;
        long capped = Math.min(raw, maxUnits > 0 ? maxUnits : raw);
        return Math.max(0, capped);
    }
}
