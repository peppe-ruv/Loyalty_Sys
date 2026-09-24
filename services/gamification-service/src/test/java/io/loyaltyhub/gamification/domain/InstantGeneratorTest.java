package io.loyaltyhub.gamification.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Generatore istanti (gamification-service.md §7: stesso seme → stessi istanti; 355 per IW-AUTUNNO). */
class InstantGeneratorTest {

    private static final Instant START = Instant.parse("2026-09-04T00:00:00Z");
    private static final Instant END = START.plus(Duration.ofDays(60));
    private static final List<InstantGenerator.PrizeQuantity> AUTUNNO = List.of(
            new InstantGenerator.PrizeQuantity("P-50", 200, 1),
            new InstantGenerator.PrizeQuantity("P-100", 100, 2),
            new InstantGenerator.PrizeQuantity("P-COFFEE", 50, 3),
            new InstantGenerator.PrizeQuantity("P-POWERBANK", 5, 4));

    @Test
    void sameSeedGivesTheSameInstants() {
        var a = InstantGenerator.generate(AUTUNNO, START, END, "BUSINESS_HOURS", 42);
        var b = InstantGenerator.generate(AUTUNNO, START, END, "BUSINESS_HOURS", 42);
        assertThat(a).hasSize(355).isEqualTo(b);
        assertThat(InstantGenerator.generate(AUTUNNO, START, END, "BUSINESS_HOURS", 43)).isNotEqualTo(a);
    }

    @Test
    void instantsStayInThePeriodAndInBusinessHours() {
        var out = InstantGenerator.generate(AUTUNNO, START, END, "BUSINESS_HOURS", 20260901);
        assertThat(out).allSatisfy(i -> {
            assertThat(i.at()).isAfterOrEqualTo(START).isBefore(END);
            assertThat(InstantGenerator.inBusinessHours(i.at())).isTrue();
        });
        assertThat(out.stream().filter(i -> i.prizeId().equals("P-POWERBANK"))).hasSize(5);
    }

    @Test
    void uniformCoversTheWholeDayAndRejectsAnEmptyPeriod() {
        var out = InstantGenerator.generate(AUTUNNO, START, END, "UNIFORM", 7);
        assertThat(out).anySatisfy(i -> assertThat(InstantGenerator.inBusinessHours(i.at())).isFalse());
        assertThatThrownBy(() -> InstantGenerator.generate(AUTUNNO, START, START, "UNIFORM", 7))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
