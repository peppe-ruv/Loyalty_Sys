package io.loyaltyhub.common.demo;

import io.loyaltyhub.common.ids.Codes;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Il reset demo deve produrre sempre gli stessi codici invito (docs/10 §1.3, F-REF-02). */
class SeededCodesTest {

    /** Seme del {@code MemberSeeder}; i valori attesi sono quelli prodotti dall'algoritmo storico su main. */
    private static final long DEMO_SEED = 20240101L;

    @Test
    void codiciDemoRiproducibiliConSemeFisso() {
        Random rnd = new Random(DEMO_SEED);
        assertThat(SeededCodes.random(8, rnd)).isEqualTo("CVMQZWLU");
        assertThat(SeededCodes.random(8, rnd)).isEqualTo("L3RZMCAK");
        assertThat(SeededCodes.random(8, rnd)).isEqualTo("P5MVY8CL");
    }

    @Test
    void stessoSemeStessiCodici() {
        assertThat(SeededCodes.random(12, new Random(7L))).isEqualTo(SeededCodes.random(12, new Random(7L)));
    }

    @Test
    void caratteriSoloDellAlfabeto() {
        String code = SeededCodes.random(200, new Random(DEMO_SEED));
        assertThat(code).hasSize(200);
        code.chars().forEach(c -> assertThat(Codes.ALPHABET).contains(String.valueOf((char) c)));
    }

    @Test
    void lunghezzaNonValida() {
        assertThatThrownBy(() -> SeededCodes.random(0, new Random(1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length deve essere > 0");
    }
}
