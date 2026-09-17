package io.loyaltyhub.contestservice.wheel;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FortuneWheelTest {
    private final FortuneWheel wheel = new FortuneWheel("w", "w", true, FortuneWheel.Mode.INSTANT_WIN_BACKED, "c", List.of(
            new FortuneWheel.Slot("win", "Premio", 10, "rw", null, 0, 5, true), new FortuneWheel.Slot("pts", "10 punti", 30, null, "PREMIO", 10, -1, true), new FortuneWheel.Slot("no", "Ritenta", 60, null, null, 0, -1, false)),
            "PREMIO", 0, 1, Period.DAY, null, null, 0, 0, "EVERYONE");

    @Test void instantWinBackedDrawNeverWinsOnLosingPlay() {
        var rnd = new SecureRandom();
        for (int i = 0; i < 200; i++) assertThat(wheel.draw(rnd, wheel.eligible(false)).winning()).isFalse();
        assertThat(wheel.probabilityOf(wheel.slots().get(0), wheel.eligible(true))).isEqualTo(0.25);
        int wins = 0; for (int i = 0; i < 5000; i++) if (wheel.draw(rnd, wheel.eligible(true)).id().equals("win")) wins++;
        assertThat(wins).isBetween(1000, 1500);
    }
}
