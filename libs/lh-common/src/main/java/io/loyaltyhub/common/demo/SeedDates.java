package io.loyaltyhub.common.demo;

import io.loyaltyhub.common.time.BusinessCalendar;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Risolve le espressioni di data relative dei seed (docs/10 §1.2) sul fuso {@code Europe/Rome}.
 * Esempi: {@code @now}, {@code @today-20d}, {@code @eom+1M}, {@code @lastSaturday}, {@code @today-3dT18:45}.
 * Così la demo è sempre "fresca" a parità di file seed.
 */
public final class SeedDates {

    private static final Pattern TIME_SUFFIX = Pattern.compile("T(\\d{2}):(\\d{2})$");
    private static final Pattern OFFSET = Pattern.compile("([+-]\\d+)([dhMy])");

    private SeedDates() {
    }

    /** Risolve l'espressione in un {@link Instant}. Un valore non-{@code @} viene letto come ISO-8601. */
    public static Instant resolve(String expr, Clock clock) {
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException("espressione data vuota");
        }
        if (expr.charAt(0) != '@') {
            return Instant.parse(expr);
        }
        Clock rome = clock.withZone(BusinessCalendar.ZONE);
        String body = expr.substring(1);

        LocalTime timeOfDay = null;
        Matcher tm = TIME_SUFFIX.matcher(body);
        if (tm.find()) {
            timeOfDay = LocalTime.of(Integer.parseInt(tm.group(1)), Integer.parseInt(tm.group(2)));
            body = body.substring(0, tm.start());
        }

        String keyword = readKeyword(body);
        String offsets = body.substring(keyword.length());
        ZonedDateTime base = base(keyword, rome);
        base = applyOffsets(base, offsets, "eom".equals(keyword));
        if (timeOfDay != null) {
            base = base.withHour(timeOfDay.getHour()).withMinute(timeOfDay.getMinute()).withSecond(0).withNano(0);
        }
        return base.toInstant();
    }

    private static String readKeyword(String body) {
        int i = 0;
        while (i < body.length() && Character.isLetter(body.charAt(i))) {
            i++;
        }
        return body.substring(0, i);
    }

    private static ZonedDateTime base(String keyword, Clock rome) {
        ZonedDateTime now = ZonedDateTime.now(rome);
        ZonedDateTime midnight = now.toLocalDate().atStartOfDay(BusinessCalendar.ZONE);
        return switch (keyword) {
            case "now" -> now;
            case "today" -> midnight;
            case "som" -> midnight.withDayOfMonth(1);
            case "eom" -> midnight.withDayOfMonth(midnight.toLocalDate().lengthOfMonth())
                    .withHour(23).withMinute(59).withSecond(59);
            case "soy" -> midnight.withDayOfYear(1);
            case "eoy" -> midnight.withMonth(12).withDayOfMonth(31).withHour(23).withMinute(59).withSecond(59);
            default -> {
                if (keyword.startsWith("last")) {
                    yield lastDayOfWeek(midnight, keyword.substring(4));
                }
                throw new IllegalArgumentException("parola chiave data sconosciuta: @" + keyword);
            }
        };
    }

    private static ZonedDateTime lastDayOfWeek(ZonedDateTime midnight, String dayName) {
        if (dayName.equalsIgnoreCase("Weekday")) {
            ZonedDateTime d = midnight.minusDays(1);
            while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                d = d.minusDays(1);
            }
            return d;
        }
        DayOfWeek target = DayOfWeek.valueOf(dayName.toUpperCase());
        ZonedDateTime d = midnight.minusDays(1);
        while (d.getDayOfWeek() != target) {
            d = d.minusDays(1);
        }
        return d;
    }

    /**
     * Applica gli scostamenti in ordine. SPEC-GAP: Q-336 — con {@code @eom} gli scostamenti in mesi o anni restano sulla
     * fine del mese d'arrivo ({@code @eom+1M} = fine del mese successivo, docs/10 §1.2 «combinabili»), non sullo stesso
     * numero di giorno (30 settembre + 1 mese = 31 ottobre, non 30).
     */
    private static ZonedDateTime applyOffsets(ZonedDateTime base, String offsets, boolean endOfMonth) {
        Matcher m = OFFSET.matcher(offsets);
        int consumed = 0;
        while (m.find()) {
            if (m.start() != consumed) {
                break;
            }
            consumed = m.end();
            long n = Long.parseLong(m.group(1));
            base = switch (m.group(2)) {
                case "d" -> base.plusDays(n);
                case "h" -> base.plusHours(n);
                case "M" -> base.plusMonths(n);
                case "y" -> base.plusYears(n);
                default -> base;
            };
            if (endOfMonth && (m.group(2).equals("M") || m.group(2).equals("y"))) {
                base = base.withDayOfMonth(base.toLocalDate().lengthOfMonth());
            }
        }
        if (consumed != offsets.length()) {
            throw new IllegalArgumentException("offset data non valido: '" + offsets + "'");
        }
        return base;
    }
}
