package io.loyaltyhub.common.time;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.time.temporal.WeekFields;

/**
 * Calendario di business su fuso {@code Europe/Rome} (docs/06 §1, tabella {@code time}).
 * Fornisce le chiavi di periodo usate da limiti campagne, obiettivi e classifiche.
 */
public class BusinessCalendar {

    /** Fuso di riferimento del programma (concorsi e operazioni a premio in Italia, docs/09 normativo). */
    public static final ZoneId ZONE = ZoneId.of("Europe/Rome");

    private final Clock clock;

    public BusinessCalendar(Clock clock) {
        this.clock = clock.withZone(ZONE);
    }

    public Instant now() {
        return clock.instant();
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public ZonedDateTime nowZoned() {
        return ZonedDateTime.now(clock);
    }

    /**
     * Chiave di periodo deterministica per la granularità indicata.
     * {@code DAY}→{@code 2026-09-19}, {@code WEEK}→{@code 2026-W38}, {@code MONTH}→{@code 2026-09},
     * {@code EDITION}/{@code YEAR}→{@code 2026}, {@code ALL_TIME}→{@code ALL}.
     */
    public String periodKey(Period period) {
        LocalDate d = today();
        return switch (period) {
            case DAY -> d.toString();
            case WEEK -> "%d-W%02d".formatted(
                    d.get(WeekFields.ISO.weekBasedYear()), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            case MONTH -> "%d-%02d".formatted(d.getYear(), d.getMonthValue());
            case EDITION, YEAR -> String.valueOf(d.getYear());
            case ALL_TIME -> "ALL";
        };
    }

    /** Codice edizione dell'anno solare che contiene oggi (docs/10 §1.2: {@code E<anno>}). */
    public String currentEditionCode() {
        return "E" + today().getYear();
    }

    /** Ultimo giorno feriale (lun-ven) strettamente precedente a oggi. */
    public LocalDate lastWeekday() {
        LocalDate d = today().minusDays(1);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    public enum Period {
        DAY, WEEK, MONTH, YEAR, EDITION, ALL_TIME
    }
}
