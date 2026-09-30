package io.loyaltyhub.common.ids;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodesTest {

    @Test
    void verifyRandomLength() {
        assertThat(Codes.random(8)).hasSize(8);
        assertThat(Codes.random(12)).hasSize(12);
    }

    @Test
    void verifyRandomCharactersInAlphabet() {
        String code = Codes.random(100);
        for (char c : code.toCharArray()) {
            assertThat(Codes.ALPHABET).contains(String.valueOf(c));
        }
    }

    @Test
    void verifyGrouped() {
        String code = Codes.grouped(3, 4);
        assertThat(code).hasSize(14); // 4 + 1 + 4 + 1 + 4
        assertThat(code).containsPattern("^[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}$");
    }

    @Test
    void verifyInvalidLength() {
        assertThatThrownBy(() -> Codes.random(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("length deve essere > 0");
    }
}
