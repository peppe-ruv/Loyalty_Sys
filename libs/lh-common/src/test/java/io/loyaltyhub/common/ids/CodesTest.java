package io.loyaltyhub.common.ids;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Generatore sicuro di codici sull'alfabeto {@code A-Z2-9} senza caratteri ambigui (docs/06 §1). */
class CodesTest {

    @Test
    void lunghezzaRichiesta() {
        assertThat(Codes.random(8)).hasSize(8);
        assertThat(Codes.random(12)).hasSize(12);
    }

    @Test
    void soloCaratteriDellAlfabeto() {
        String code = Codes.random(100);
        code.chars().forEach(c -> assertThat(Codes.ALPHABET).contains(String.valueOf((char) c)));
    }

    @Test
    void codiciAGruppi() {
        String code = Codes.grouped(3, 4);
        assertThat(code).hasSize(14); // 4 + 1 + 4 + 1 + 4
        assertThat(code).containsPattern("^[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}$");
    }

    @Test
    void lunghezzaNonValida() {
        assertThatThrownBy(() -> Codes.random(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("length deve essere > 0");
    }

    @Test
    void dimensioneGruppoNonValida() {
        assertThatThrownBy(() -> Codes.grouped(1, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("length deve essere > 0");
    }
}
