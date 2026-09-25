package io.loyaltyhub.engagement.domain;

import io.loyaltyhub.engagement.TestbookRows;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.aggregator.ArgumentsAccessor;
import org.junit.jupiter.params.provider.CsvFileSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Testbook TB-ENG (docs/testbook/TB-ENG-engagement.md): tema del portale, logica pura ({@link Theme}). Righe THM:
 * formato esadecimale dei colori e calcolo del contrasto WCAG. Le soglie 4,5:1 via API sono in TestbookEngContentIT.
 */
class TestbookEngThemeTest {

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/engagement/thm.csv", numLinesToSkip = 1, delimiter = '\t', quoteCharacter = '~',
            maxCharsPerColumn = 8192)
    void hexFormat(ArgumentsAccessor row) throws Exception {
        String[] c = TestbookRows.columns(row);
        hexFormat(c[0], c[1], c[2], Boolean.parseBoolean(c[3]));
    }

    private void hexFormat(String id, String description, String value, boolean expected) {
        // TESTBOOK: ambiguo, vedi TB-ENG-THM-005, -006, -007 (senza #, forma corta, alfa).
        String v = switch (value) {
            case "<null>" -> null;
            case "<empty>" -> "";
            default -> value;
        };
        assertThat(Theme.isHex(v)).isEqualTo(expected);
    }

    @Test
    @DisplayName("[TB-ENG-THM-015] contrasto #000000 su #FFFFFF: 21:1")
    void contrastExtremes() {
        assertThat(Theme.contrast("#000000", "#FFFFFF")).isCloseTo(21.0, within(1e-9));
    }

    @Test
    @DisplayName("[TB-ENG-THM-016] contrasto di un colore con sé stesso: 1:1")
    void contrastSame() {
        assertThat(Theme.contrast("#1FB98F", "#1FB98F")).isCloseTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("[TB-ENG-THM-017] contrasto simmetrico")
    void contrastSymmetric() {
        assertThat(Theme.contrast("#0E1B2C", "#1FB98F")).isCloseTo(Theme.contrast("#1FB98F", "#0E1B2C"), within(1e-12));
        assertThat(Theme.contrast("#000000", "#577B76")).isGreaterThanOrEqualTo(4.5);
        assertThat(Theme.contrast("#000000", "#457E76")).isLessThan(4.5);
    }
}
