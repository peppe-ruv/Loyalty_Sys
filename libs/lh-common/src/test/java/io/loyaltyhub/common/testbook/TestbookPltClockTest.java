package io.loyaltyhub.common.testbook;

import io.loyaltyhub.common.demo.SeedDates;
import io.loyaltyhub.common.time.BusinessCalendar;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import static io.loyaltyhub.common.testbook.TestbookPltSupport.at;
import static io.loyaltyhub.common.testbook.TestbookPltSupport.decode;
import static io.loyaltyhub.common.testbook.TestbookPltSupport.outcome;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-PLT §CLK e §CAL — tempo di business (docs/10 §1.2, docs/06 §1 {@code time}): espressioni di data relative dei seed
 * ({@link SeedDates}) e chiavi di periodo ({@link BusinessCalendar}) sul fuso {@code Europe/Rome}, con orologio fisso.
 * Confini provati: mezzanotte di Roma (23:59:59 / 00:00), cambio dell'ora di marzo e ottobre, fine mese, 29 febbraio,
 * settimana ISO a cavallo d'anno, giorno feriale e sabato «precedenti».
 */
class TestbookPltClockTest {

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/plt/seed-dates.csv", numLinesToSkip = 1)
    void seedDates(String id, String description, String clock, String expression, String expected) {
        String got = outcome(() -> SeedDates.resolve(decode(expression), at(clock)));
        assertThat(got).as("%s: %s", id, description).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/plt/calendar.csv", numLinesToSkip = 1)
    void calendar(String id, String description, String clock, String function, String expected) {
        BusinessCalendar cal = new BusinessCalendar(at(clock));
        String got = outcome(() -> switch (function) {
            case "edition" -> cal.currentEditionCode();
            case "lastWeekday" -> cal.lastWeekday();
            case "today" -> cal.today();
            case "zone" -> BusinessCalendar.ZONE;
            default -> cal.periodKey(BusinessCalendar.Period.valueOf(function));
        });
        assertThat(got).as("%s: %s", id, description).isEqualTo(expected);
    }
}
