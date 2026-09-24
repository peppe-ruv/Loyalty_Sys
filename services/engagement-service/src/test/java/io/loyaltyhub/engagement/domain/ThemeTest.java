package io.loyaltyhub.engagement.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Contrasto WCAG (docs/servizi/engagement-service.md §3: testo/primario ≥ 4,5) e colori esadecimali. */
class ThemeTest {

    @Test
    void contrastFollowsWcag() {
        assertThat(Theme.contrast("#000000", "#FFFFFF")).isCloseTo(21.0, within(0.01));
        assertThat(Theme.contrast("#FFFFFF", "#FFFFFF")).isCloseTo(1.0, within(0.001));
        // Aurora: testo night su primary e su bg sopra la soglia AA.
        assertThat(Theme.contrast("#0E1B2C", "#1FB98F")).isGreaterThan(Theme.MIN_CONTRAST);
        assertThat(Theme.contrast("#0E1B2C", "#F3F7F9")).isGreaterThan(Theme.MIN_CONTRAST);
        assertThat(Theme.contrast("#0E1B2C", "#2A3A55")).isLessThan(Theme.MIN_CONTRAST);
    }

    @Test
    void acceptsOnlyFullHexColors() {
        assertThat(Theme.isHex("#1FB98F")).isTrue();
        assertThat(Theme.isHex("#1fb98f")).isTrue();
        assertThat(Theme.isHex("#FFF")).isFalse();
        assertThat(Theme.isHex("green")).isFalse();
        assertThat(Theme.isHex(null)).isFalse();
    }
}
