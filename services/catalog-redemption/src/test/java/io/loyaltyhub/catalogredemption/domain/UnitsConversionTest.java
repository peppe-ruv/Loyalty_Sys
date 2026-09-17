package io.loyaltyhub.catalogredemption.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * «Paga con i punti» (RF-104) non deve mai scontare più del carrello: si arrotonda per difetto al
 * passo e il resto si paga normalmente. Conversione di esempio: 100 punti = 1 €, taglio minimo 100,
 * passo 100.
 */
class UnitsConversionTest {

    private final UnitsConversion conv = new UnitsConversion(new BigDecimal("0.01"), 100, 0, 100);

    @Test void nonScontaMaiPiuDellImporto() {
        long unita = conv.unitsFor(new BigDecimal("5.55"));

        assertThat(unita).isEqualTo(500);
        assertThat(conv.valueOf(unita)).isEqualByComparingTo("5.00");
    }

    @Test void unImportoMultiploDelPassoSiCopreTutto() {
        assertThat(conv.unitsFor(new BigDecimal("6.00"))).isEqualTo(600);
        assertThat(conv.valueOf(600)).isEqualByComparingTo("6.00");
    }

    @Test void sottoIlTaglioMinimoNonSiPagaConIPunti() {
        assertThat(conv.unitsFor(new BigDecimal("0.50"))).isZero();
    }

    @Test void esattamenteIlTaglioMinimo() {
        assertThat(conv.unitsFor(new BigDecimal("1.00"))).isEqualTo(100);
    }

    @Test void ilMassimoConfigurato() {
        var conMassimo = new UnitsConversion(new BigDecimal("0.01"), 100, 500, 100);

        assertThat(conMassimo.unitsFor(new BigDecimal("50.00"))).isEqualTo(500);
    }
}
