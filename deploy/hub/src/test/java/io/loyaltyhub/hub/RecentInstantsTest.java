package io.loyaltyhub.hub;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class RecentInstantsTest {

    @Test
    void weekdayMorningIsARecentTuesdayAtTen() {
        Instant t = Instant.parse(RecentInstants.weekdayMorning());
        assertThat(t.atOffset(ZoneOffset.UTC).getDayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
        assertThat(t.atOffset(ZoneOffset.UTC).toLocalTime().toString()).isEqualTo("10:00");
        assertThat(t).isBefore(Instant.now());
        assertThat(Duration.between(t, Instant.now())).isLessThanOrEqualTo(Duration.ofDays(7).plusHours(14));
    }

    @Test
    void recentIsWithinTwoHours() {
        Instant t = Instant.parse(RecentInstants.recent());
        assertThat(t).isBefore(Instant.now());
        assertThat(Duration.between(t, Instant.now())).isLessThan(Duration.ofHours(2));
    }
}
