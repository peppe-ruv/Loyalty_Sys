package io.loyaltyhub.hub;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/**
 * Istanti relativi a "adesso" per gli IT che inviano eventi all'ingestion, che rifiuta
 * gli eventi più vecchi di 30 giorni: le date fisse nel codice prima o poi scadono.
 */
final class RecentInstants {

    private RecentInstants() {
    }

    /**
     * Il martedì più recente alle 10:00Z, strettamente prima di oggi (1-7 giorni fa).
     * Sta dentro la finestra di 30 giorni dell'ingestion, non è mai nel weekend (la campagna
     * "Weekend a punti doppi" usa SAT/SUN), non cade mai nella finestra del Black Friday
     * (ven 27 - lun 30 novembre 2026) ed è lo stesso giorno della settimana della vecchia
     * data fissa 2026-09-15 (un martedì).
     */
    static String weekdayMorning() {
        LocalDate tuesday = LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.previous(DayOfWeek.TUESDAY));
        return tuesday.atTime(10, 0).toInstant(ZoneOffset.UTC).toString();
    }

    /** Un'ora fa, per i test in cui il giorno della settimana non conta. */
    static String recent() {
        return Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
