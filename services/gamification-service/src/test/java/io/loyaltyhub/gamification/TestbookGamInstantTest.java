package io.loyaltyhub.gamification;

import io.loyaltyhub.gamification.domain.InstantGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class TestbookGamInstantTest {

    private static final Instant START = Instant.parse("2026-09-04T00:00:00Z");
    private static final Instant END = START.plus(Duration.ofDays(60));
    private static final List<InstantGenerator.PrizeQuantity> PRIZES = List.of(
            new InstantGenerator.PrizeQuantity("P-1", 100, 1));

    @Test
    @DisplayName("[TB-GAM-INST-001] UNIFORM, quantity=100")
    void testGeneratorUniform() {
        var instants = InstantGenerator.generate(PRIZES, START, END, "UNIFORM", 42L);
        assertThat(instants).hasSize(100);
        // It can be generated outside business hours, although it is random so we just assert size
    }

    @Test
    @DisplayName("[TB-GAM-INST-002] BUSINESS_HOURS, quantity=100")
    void testGeneratorBusinessHours() {
        var instants = InstantGenerator.generate(PRIZES, START, END, "BUSINESS_HOURS", 42L);
        assertThat(instants).hasSize(100);
        // Assert all generated instants are within business hours
        for (var i : instants) {
            assertThat(InstantGenerator.inBusinessHours(i.at())).isTrue();
        }
    }

    @Test
    @DisplayName("[TB-GAM-INST-003] Stesso seed, stessi parametri")
    void testGeneratorSeedDeterminism() {
        var a = InstantGenerator.generate(PRIZES, START, END, "BUSINESS_HOURS", 42L);
        var b = InstantGenerator.generate(PRIZES, START, END, "BUSINESS_HOURS", 42L);
        assertThat(a).hasSize(100).isEqualTo(b);

        var c = InstantGenerator.generate(PRIZES, START, END, "BUSINESS_HOURS", 43L);
        assertThat(a).isNotEqualTo(c);
    }
}
