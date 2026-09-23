package io.loyaltyhub.ingestion.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;

/**
 * Istante di un passo di scenario (campo {@code at}, docs/10 §9), relativo a oggi in {@code Europe/Rome} così gli
 * scenari restano deterministici: {@code @lastWeekdayT10:30} (ultimo lunedì–venerdì), {@code @lastSaturdayT11:00}
 * (e gli altri giorni in inglese), oppure un istante ISO-8601. Sempre l'ultima occorrenza non futura rispetto a
 * {@code now}. Assente → {@code now}.
 */
public final class ScenarioTime {

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");

    private ScenarioTime() {
    }

    public static Instant resolve(String at, Instant now) {
        if (at == null || at.isBlank()) {
            return now;
        }
        if (!at.startsWith("@last")) {
            return Instant.parse(at);
        }
        int t = at.indexOf('T');
        if (t < 0) {
            throw new IllegalArgumentException("Segnaposto temporale senza orario: " + at);
        }
        String day = at.substring("@last".length(), t).toUpperCase(Locale.ROOT);
        LocalTime time = LocalTime.parse(at.substring(t + 1));
        ZonedDateTime current = now.atZone(ROME);
        for (int back = 0; back <= 7; back++) {
            LocalDate d = current.toLocalDate().minusDays(back);
            ZonedDateTime candidate = d.atTime(time).atZone(ROME);
            if (candidate.isAfter(current) || !matches(d.getDayOfWeek(), day)) {
                continue;
            }
            return candidate.toInstant();
        }
        throw new IllegalArgumentException("Segnaposto temporale non riconosciuto: " + at);
    }

    private static boolean matches(DayOfWeek dow, String token) {
        if (token.equals("WEEKDAY")) {
            return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
        }
        return dow.name().equals(token);
    }
}
