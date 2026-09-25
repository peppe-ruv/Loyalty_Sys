package io.loyaltyhub.ingestion.domain;

import io.loyaltyhub.common.demo.SeedDates;
import io.loyaltyhub.common.time.BusinessCalendar;

import java.time.Clock;
import java.time.Instant;

/**
 * Istante di un passo di scenario (campo {@code at}, docs/10 §8): un'espressione di data di docs/10 §1 risolta su
 * {@code Europe/Rome} rispetto a {@code now}, con la stessa grammatica dei seed ({@link SeedDates}):
 * {@code @now}, {@code @today-1dT10:30}, {@code @som}…, {@code @lastWeekday} / {@code @lastSaturday} (ultimo giorno
 * feriale / ultimo sabato <em>precedente</em> a oggi, alle 00:00 se manca il suffisso {@code T10:30}), oppure un
 * istante ISO-8601. Assente → {@code @now}. Espressione non valida → {@link RuntimeException}.
 */
public final class ScenarioTime {

    private ScenarioTime() {
    }

    public static Instant resolve(String at, Instant now) {
        if (at == null || at.isBlank()) {
            return now;
        }
        return SeedDates.resolve(at.trim(), Clock.fixed(now, BusinessCalendar.ZONE));
    }
}
