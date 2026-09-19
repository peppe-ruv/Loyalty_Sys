package io.loyaltyhub.common.time;

import io.loyaltyhub.common.demo.SeedDates;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeedDatesTest {

    // Giovedì 2026-09-17 12:00 UTC (14:00 Europe/Rome, ora legale).
    private final Clock fixed = Clock.fixed(Instant.parse("2026-09-17T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void resolvesTodayAtRomeMidnight() {
        Instant r = SeedDates.resolve("@today", fixed);
        // Mezzanotte di Roma del 17/09 = 2026-09-16T22:00:00Z (offset +02:00).
        assertThat(r).isEqualTo(Instant.parse("2026-09-16T22:00:00Z"));
    }

    @Test
    void appliesDayOffset() {
        Instant base = SeedDates.resolve("@today", fixed);
        Instant minus20 = SeedDates.resolve("@today-20d", fixed);
        assertThat(minus20).isEqualTo(base.minus(java.time.Duration.ofDays(20)));
    }

    @Test
    void applifiesHourOffsetFromNow() {
        Instant now = SeedDates.resolve("@now", fixed);
        assertThat(now).isEqualTo(fixed.instant());
        assertThat(SeedDates.resolve("@now-3h", fixed)).isEqualTo(now.minus(java.time.Duration.ofHours(3)));
    }

    @Test
    void endOfMonthAndCombinedOffset() {
        // @eom = 30/09 23:59:59 Roma; @eom+1M = 30/10 23:59:59 Roma.
        ZonedDateTime eom = ZonedDateTime.ofInstant(SeedDates.resolve("@eom", fixed), BusinessCalendar.ZONE);
        assertThat(eom.getMonthValue()).isEqualTo(9);
        assertThat(eom.getDayOfMonth()).isEqualTo(30);
        assertThat(eom.getHour()).isEqualTo(23);
        ZonedDateTime eomPlus = ZonedDateTime.ofInstant(SeedDates.resolve("@eom+1M", fixed), BusinessCalendar.ZONE);
        assertThat(eomPlus.getMonthValue()).isEqualTo(10);
    }

    @Test
    void lastSaturdayIsPreviousSaturday() {
        // Oggi è giovedì 17/09; l'ultimo sabato precedente è il 12/09.
        ZonedDateTime sat = ZonedDateTime.ofInstant(SeedDates.resolve("@lastSaturday", fixed), BusinessCalendar.ZONE);
        assertThat(sat.getDayOfMonth()).isEqualTo(12);
        assertThat(sat.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.SATURDAY);
    }

    @Test
    void timeOfDaySuffix() {
        ZonedDateTime dt = ZonedDateTime.ofInstant(SeedDates.resolve("@today-3dT18:45", fixed), BusinessCalendar.ZONE);
        assertThat(dt.getHour()).isEqualTo(18);
        assertThat(dt.getMinute()).isEqualTo(45);
        assertThat(dt.getDayOfMonth()).isEqualTo(14);
    }

    @Test
    void rejectsUnknownKeyword() {
        assertThatThrownBy(() -> SeedDates.resolve("@whenever", fixed))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
