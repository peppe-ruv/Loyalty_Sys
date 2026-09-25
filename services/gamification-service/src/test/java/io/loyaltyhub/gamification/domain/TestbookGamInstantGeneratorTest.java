package io.loyaltyhub.gamification.domain;

import io.loyaltyhub.gamification.domain.InstantGenerator.GeneratedInstant;
import io.loyaltyhub.gamification.domain.InstantGenerator.PrizeQuantity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testbook TB-GAM — generatore degli istanti vincenti ({@code GEN}) (docs/testbook/TB-GAM-gioco.md §9). Oracolo:
 * docs/03 §6 (un istante per unità, {@code [startAt, endAt)}, UNIFORM, BUSINESS_HOURS 08–22 Europe/Rome, seme),
 * gamification §5 (SplittableRandom, ordine di {@code sort_order}), §7 (stesso seme → stessi istanti), F-IW-03.
 * I confini orari sono verificati con un calcolo indipendente sul fuso di Roma.
 */
class TestbookGamInstantGeneratorTest {

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gam/fasce-orarie.csv", numLinesToSkip = 1)
    void fasciaOraria(String id, String desc, String instant, boolean expected) {
        assertThat(InstantGenerator.inBusinessHours(Instant.parse(instant))).isEqualTo(expected);
    }

    @Test
    @DisplayName("[TB-GAM-GEN-021] un istante per unità di premio: 3, 1 e 5")
    void onePerUnit() {
        List<GeneratedInstant> out = InstantGenerator.generate(List.of(new PrizeQuantity("A", 3, 1), new PrizeQuantity("B", 1, 2),
                new PrizeQuantity("C", 5, 3)), Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"), "UNIFORM", 7);
        Map<String, Long> byPrize = out.stream().collect(Collectors.groupingBy(GeneratedInstant::prizeId, Collectors.counting()));
        assertThat(out).hasSize(9);
        assertThat(byPrize).containsExactlyInAnyOrderEntriesOf(Map.of("A", 3L, "B", 1L, "C", 5L));
    }

    @Test
    @DisplayName("[TB-GAM-GEN-022] UNIFORM: tutti gli istanti in [startAt, endAt)")
    void uniformWithinPeriod() {
        Instant start = Instant.parse("2026-09-01T10:00:00Z");
        Instant end = Instant.parse("2026-09-01T11:00:00Z");
        List<GeneratedInstant> out = InstantGenerator.generate(List.of(new PrizeQuantity("A", 2000, 1)), start, end, "UNIFORM", 11);
        assertThat(out).allSatisfy(g -> assertThat(g.at()).isAfterOrEqualTo(start).isBefore(end));
    }

    @Test
    @DisplayName("[TB-GAM-GEN-023] UNIFORM su 10 giorni: istanti anche di notte, fuori 08–22")
    void uniformCoversNight() {
        List<GeneratedInstant> out = InstantGenerator.generate(List.of(new PrizeQuantity("A", 1000, 1)),
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-11T00:00:00Z"), "UNIFORM", 13);
        assertThat(out).anySatisfy(g -> assertThat(romeHour(g.at()) < 8 || romeHour(g.at()) >= 22).isTrue());
        assertThat(out).anySatisfy(g -> assertThat(romeHour(g.at())).isBetween(8, 21));
    }

    @Test
    @DisplayName("[TB-GAM-GEN-024] BUSINESS_HOURS attorno al passaggio all'ora legale: tutti tra 08:00 e 22:00 di Roma")
    void businessHoursMarchDst() {
        List<GeneratedInstant> out = InstantGenerator.generate(List.of(new PrizeQuantity("A", 1000, 1)),
                Instant.parse("2026-03-25T00:00:00Z"), Instant.parse("2026-04-04T00:00:00Z"), "BUSINESS_HOURS", 17);
        assertThat(out).hasSize(1000).allSatisfy(g -> assertThat(romeHour(g.at())).isBetween(8, 21));
        assertThat(out).anySatisfy(g -> assertThat(LocalDate.ofInstant(g.at(), ROME)).isEqualTo(LocalDate.parse("2026-03-29")));
    }

    @Test
    @DisplayName("[TB-GAM-GEN-025] BUSINESS_HOURS nel giorno del ritorno all'ora solare: tutti tra 08:00 e 22:00 di Roma")
    void businessHoursOctoberDst() {
        List<GeneratedInstant> out = InstantGenerator.generate(List.of(new PrizeQuantity("A", 500, 1)),
                Instant.parse("2026-10-24T22:00:00Z"), Instant.parse("2026-10-25T23:00:00Z"), "BUSINESS_HOURS", 19);
        assertThat(out).hasSize(500).allSatisfy(g -> {
            assertThat(romeHour(g.at())).isBetween(8, 21);
            assertThat(LocalDate.ofInstant(g.at(), ROME)).isEqualTo(LocalDate.parse("2026-10-25"));
        });
    }

    @Test
    @DisplayName("[TB-GAM-GEN-026] stesso seme e stessi parametri: stessi istanti")
    void sameSeedSameInstants() {
        assertThat(sample(42, List.of(new PrizeQuantity("A", 50, 1), new PrizeQuantity("B", 20, 2))))
                .isEqualTo(sample(42, List.of(new PrizeQuantity("A", 50, 1), new PrizeQuantity("B", 20, 2))));
    }

    @Test
    @DisplayName("[TB-GAM-GEN-027] seme diverso: istanti diversi")
    void otherSeedOtherInstants() {
        assertThat(sample(42, List.of(new PrizeQuantity("A", 50, 1)))).isNotEqualTo(sample(43, List.of(new PrizeQuantity("A", 50, 1))));
    }

    @Test
    @DisplayName("[TB-GAM-GEN-028] premi elencati in ordine diverso: stessi istanti (conta sort_order)")
    void inputOrderIrrelevant() {
        assertThat(sample(5, List.of(new PrizeQuantity("A", 10, 1), new PrizeQuantity("B", 10, 2))))
                .isEqualTo(sample(5, List.of(new PrizeQuantity("B", 10, 2), new PrizeQuantity("A", 10, 1))));
    }

    @Test
    @DisplayName("[TB-GAM-GEN-029] ordine di sort_order: prima gli istanti del premio con sort_order minore")
    void sortOrderFirst() {
        List<GeneratedInstant> out = sample(5, List.of(new PrizeQuantity("Z", 2, 9), new PrizeQuantity("A", 3, 1)));
        assertThat(out.stream().map(GeneratedInstant::prizeId).toList()).containsExactly("A", "A", "A", "Z", "Z");
    }

    @Test
    @DisplayName("[TB-GAM-GEN-030] endAt uguale a startAt: periodo vuoto, errore")
    void emptyPeriod() {
        Instant t = Instant.parse("2026-09-01T10:00:00Z");
        assertThatThrownBy(() -> InstantGenerator.generate(List.of(new PrizeQuantity("A", 1, 1)), t, t, "UNIFORM", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("[TB-GAM-GEN-031] endAt prima di startAt: errore")
    void reversedPeriod() {
        Instant t = Instant.parse("2026-09-01T10:00:00Z");
        assertThatThrownBy(() -> InstantGenerator.generate(List.of(new PrizeQuantity("A", 1, 1)), t, t.minusSeconds(1), "UNIFORM", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("[TB-GAM-GEN-032] periodo di 1 ms: tutti gli istanti a startAt")
    void oneMillisecond() {
        Instant t = Instant.parse("2026-09-01T10:00:00Z");
        List<GeneratedInstant> out = InstantGenerator.generate(List.of(new PrizeQuantity("A", 3, 1)), t, t.plusMillis(1), "UNIFORM", 3);
        assertThat(out).extracting(GeneratedInstant::at).containsOnly(t);
    }

    // TESTBOOK: ambiguo, vedi TB-GAM-GEN-033 (BUSINESS_HOURS senza ore utili: nessuna fonte)
    @Test
    @DisplayName("[TB-GAM-GEN-033] BUSINESS_HOURS su un periodo 01:00–02:00 di Roma: errore")
    void noBusinessHours() {
        assertThatThrownBy(() -> InstantGenerator.generate(List.of(new PrizeQuantity("A", 1, 1)),
                Instant.parse("2026-01-15T00:00:00Z"), Instant.parse("2026-01-15T01:00:00Z"), "BUSINESS_HOURS", 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("[TB-GAM-GEN-034] BUSINESS_HOURS su [07:59:59.999, 08:00:00.001) di Roma: tutti alle 08:00:00.000")
    void openingBoundary() {
        List<GeneratedInstant> out = InstantGenerator.generate(List.of(new PrizeQuantity("A", 20, 1)),
                Instant.parse("2026-01-15T06:59:59.999Z"), Instant.parse("2026-01-15T07:00:00.001Z"), "BUSINESS_HOURS", 23);
        assertThat(out).extracting(GeneratedInstant::at).containsOnly(Instant.parse("2026-01-15T07:00:00Z"));
    }

    @Test
    @DisplayName("[TB-GAM-GEN-035] BUSINESS_HOURS su [21:59:59.999, 22:00:00.001) di Roma: tutti alle 21:59:59.999")
    void closingBoundary() {
        List<GeneratedInstant> out = InstantGenerator.generate(List.of(new PrizeQuantity("A", 20, 1)),
                Instant.parse("2026-01-15T20:59:59.999Z"), Instant.parse("2026-01-15T21:00:00.001Z"), "BUSINESS_HOURS", 29);
        assertThat(out).extracting(GeneratedInstant::at).containsOnly(Instant.parse("2026-01-15T20:59:59.999Z"));
    }

    @Test
    @DisplayName("[TB-GAM-GEN-036] premio con quantità 0: nessun istante")
    void zeroQuantity() {
        assertThat(sample(3, List.of(new PrizeQuantity("A", 0, 1)))).isEmpty();
    }

    @Test
    @DisplayName("[TB-GAM-GEN-037] BUSINESS_HOURS sul 29 febbraio 2028: tutti quel giorno tra 08:00 e 22:00 di Roma")
    void leapDay() {
        List<GeneratedInstant> out = InstantGenerator.generate(List.of(new PrizeQuantity("A", 200, 1)),
                Instant.parse("2028-02-28T23:00:00Z"), Instant.parse("2028-02-29T23:00:00Z"), "BUSINESS_HOURS", 31);
        assertThat(out).allSatisfy(g -> {
            assertThat(LocalDate.ofInstant(g.at(), ROME)).isEqualTo(LocalDate.parse("2028-02-29"));
            assertThat(romeHour(g.at())).isBetween(8, 21);
        });
    }

    private static List<GeneratedInstant> sample(long seed, List<PrizeQuantity> prizes) {
        return InstantGenerator.generate(prizes, Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"),
                "UNIFORM", seed);
    }

    private static int romeHour(Instant at) {
        return ZonedDateTime.ofInstant(at, ROME).getHour();
    }
}
