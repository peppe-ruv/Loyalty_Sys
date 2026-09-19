package io.loyaltyhub.campaign.engine;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;

/**
 * Chiave del periodo di un limite (docs/03 §3.5) su {@code Europe/Rome}. Motore e servizio devono usare la
 * stessa chiave: il primo la legge per il controllo, il secondo per l'incremento atomico.
 */
public final class PeriodKeys {

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private PeriodKeys() {
    }

    public static String of(String period, Instant time) {
        ZonedDateTime t = time.atZone(ROME);
        return switch (period) {
            case "DAY" -> t.toLocalDate().toString();
            case "WEEK" -> t.get(IsoFields.WEEK_BASED_YEAR) + "-W"
                    + String.format("%02d", t.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            case "MONTH" -> t.format(MONTH);
            case "EDITION" -> String.valueOf(t.getYear()); // edizioni complete in M3
            default -> "ALWAYS";
        };
    }
}
