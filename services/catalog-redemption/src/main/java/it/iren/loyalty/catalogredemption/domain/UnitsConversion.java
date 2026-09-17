package it.iren.loyalty.catalogredemption.domain;

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
    /** Unità necessarie per coprire un importo (arrotondate per eccesso al passo). */
    public long unitsFor(BigDecimal eur) {
        long raw = eur.divide(eurPerUnit, 0, RoundingMode.CEILING).longValue();
        if (stepUnits > 0) raw = minUnits + (long) Math.ceil(Math.max(0, raw - minUnits) / (double) stepUnits) * stepUnits;
        return Math.max(minUnits, raw);
    }
}
