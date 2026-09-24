package io.loyaltyhub.engagement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import static org.assertj.core.api.Assertions.assertThat;

public class TestbookEngThemeTest {

    @ParameterizedTest(name = "[{0}] bg: {1}, night: {2}")
    @CsvFileSource(resources = "/testbook/engagement/TB-ENG-theme.csv", numLinesToSkip = 1)
    void testbookThemeContrast(String id, String bg, String night, boolean expected) {
        double contrast = Theme.contrast(bg, night);
        boolean isValid = contrast >= Theme.MIN_CONTRAST;
        assertThat(isValid).isEqualTo(expected);
    }

    @Test
    @DisplayName("[TB-ENG-THM-003] Hex invalid format")
    void hexInvalidFormat() {
        assertThat(Theme.isHex("red")).isFalse();
        assertThat(Theme.isHex("000000")).isFalse();
        assertThat(Theme.isHex("#000")).isFalse(); // only 6 chars accepted
        assertThat(Theme.isHex("#000000")).isTrue();
    }
}
