package io.loyaltyhub.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Paginazione (docs/06 §2; Q-332, Q-532 causa (2), F2-SEC-12): {@code page × size} non deve mai andare in overflow,
 * altrimenti {@code OFFSET} diventa negativo e Postgres risponde con un 500 invece di un 400.
 */
class PageParamsTest {

    @Test
    @DisplayName("valori normali: offset = page × size, size ridotta a 100")
    void normalValues() {
        assertThat(PageParams.of(0, 20).offset()).isZero();
        assertThat(PageParams.of(3, 20).offset()).isEqualTo(60);
        PageParams capped = PageParams.of(2, 5_000);
        assertThat(capped.size()).isEqualTo(PageParams.MAX_SIZE);
        assertThat(capped.offset()).isEqualTo(200);
    }

    @ParameterizedTest(name = "page={0} size={1} → offset {2}")
    @CsvSource({
            // il confine esatto: page × size = Integer.MAX_VALUE è ammesso
            "2147483647, 1, 2147483647",
            "1073741823, 2, 2147483646",
            "21474836, 100, 2147483600",
            // size oltre il massimo è ridotta a 100 prima del prodotto
            "21474836, 5000, 2147483600",
            "0, 100, 0"})
    @DisplayName("confine esatto: ammesso, e offset coincide col prodotto esatto")
    void exactBoundaryIsAccepted(int page, int size, int expectedOffset) {
        assertThat(PageParams.of(page, size).offset()).isEqualTo(expectedOffset);
    }

    @ParameterizedTest(name = "page={0} size={1} → 400")
    @CsvSource({
            "2147483647, 2",
            "2147483646, 100",
            "1073741824, 2",
            "21474837, 100",
            // size 5000 è ridotta a 100: il prodotto si valuta sul valore effettivo
            "21474837, 5000",
            "2147483647, 2147483647"})
    @DisplayName("overflow di page × size: 400 BAD_REQUEST, mai un offset negativo")
    void overflowIsBadRequest(int page, int size) {
        assertThatThrownBy(() -> PageParams.of(page, size)).isInstanceOfSatisfying(LhException.class, e -> {
            assertThat(e.status().value()).isEqualTo(400);
            assertThat(e.code()).isEqualTo("BAD_REQUEST");
            assertThat(e.getMessage()).contains("page");
        });
    }

    @Test
    @DisplayName("page negativa o size < 1 restano 400 (Q-332)")
    void invalidValuesAreStillBadRequest() {
        assertThatThrownBy(() -> PageParams.of(-1, 10)).isInstanceOf(LhException.class);
        assertThatThrownBy(() -> PageParams.of(0, 0)).isInstanceOf(LhException.class);
        assertThatThrownBy(() -> PageParams.of(0, -5)).isInstanceOf(LhException.class);
    }

    @Test
    @DisplayName("un record costruito senza of() non dà mai un offset negativo per overflow: fallisce")
    void offsetNeverWrapsAround() {
        assertThatThrownBy(() -> new PageParams(Integer.MAX_VALUE, 100).offset()).isInstanceOf(ArithmeticException.class);
        assertThat(new PageParams(Integer.MAX_VALUE, 1).offset()).isEqualTo(Integer.MAX_VALUE);
    }
}
