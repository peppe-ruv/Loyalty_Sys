package io.loyaltyhub.wallet.domain;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.statistics.Statistics;
import net.jqwik.api.Tuple;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proprietà della chiusura edizione (docs/03 §4.3, F-TIER-03, ADR-053 decisione 1, Q-690) su scale dei livelli e punti
 * status generati: discesa morbida (mai più di un gradino), nessuna salita in chiusura, monotonia rispetto ai punti
 * status, esito coerente, livello sconosciuto (Q-149). Testano {@link EditionCloseRule}, la funzione che il job di
 * chiusura usa davvero; la scala di prova ha sempre BASE a soglia 0, come la scala del programma.
 */
class EditionCloseRuleProperties {

    /** Scala, livello attuale (indice = rank) e punti status dell'edizione che si chiude. */
    record Scenario(List<Tier> scale, int currentIdx, long periodSts) {
        Tier current() {
            return scale.get(currentIdx);
        }
    }

    @Provide
    Arbitrary<List<Tier>> scales() {
        // 2..6 livelli; BASE a soglia 0, soglie strettamente crescenti col rank (TIER_THRESHOLDS_NOT_MONOTONIC).
        return Arbitraries.longs().between(1, 5_000).list().ofMinSize(1).ofMaxSize(5).map(gaps -> {
            List<Tier> scale = new ArrayList<>();
            scale.add(tier(0, 0));
            long threshold = 0;
            for (int i = 0; i < gaps.size(); i++) {
                threshold += gaps.get(i);
                scale.add(tier(i + 1, threshold));
            }
            return List.copyOf(scale);
        });
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        return scales().flatMap(scale -> {
            long top = scale.getLast().thresholdSts();
            // Punti status: uniformi su tutto l'intervallo, più i valori a ridosso di ogni soglia (soglia − 1, soglia, + 1).
            Arbitrary<Long> boundary = Arbitraries.of(scale).flatMap(t -> Arbitraries.of(
                    Math.max(0, t.thresholdSts() - 1), t.thresholdSts(), t.thresholdSts() + 1));
            Arbitrary<Long> sts = Arbitraries.frequencyOf(
                    Tuple.of(3, Arbitraries.longs().between(0, top * 2 + 100)),
                    Tuple.of(2, boundary));
            return Combinators.combine(Arbitraries.integers().between(0, scale.size() - 1), sts)
                    .as((idx, p) -> new Scenario(scale, idx, p));
        });
    }

    @Provide
    Arbitrary<Long> extraPoints() {
        return Arbitraries.longs().between(0, 20_000);
    }

    private static Tier tier(int rank, long threshold) {
        return new Tier("T" + rank, "Livello " + rank, rank, threshold, BigDecimal.ONE, List.of(), null, null);
    }

    private static int rank(Scenario s, String code) {
        return s.scale().stream().filter(t -> t.code().equals(code)).findFirst().orElseThrow().rank();
    }

    /** Discesa morbida: mai oltre un gradino sotto l'attuale, mai sopra l'attuale, mai sotto il livello guadagnato. */
    @Property(tries = 1000)
    void softDescentNeverDropsMoreThanOneStepAndNeverRises(@ForAll("scenarios") Scenario s) {
        var res = EditionCloseRule.computeNext(s.current().code(), s.periodSts(), s.scale());
        int cur = s.currentIdx();
        int earned = rank(s, res.earnedTier());
        int next = rank(s, res.newTier());
        int floor = Math.max(0, cur - 1);

        assertThat(next).as("la salita non avviene mai in chiusura").isLessThanOrEqualTo(cur);
        assertThat(next).as("discesa morbida: al massimo un gradino").isGreaterThanOrEqualTo(floor);
        assertThat(next).as("chi ha guadagnato il livello lo tiene").isGreaterThanOrEqualTo(Math.min(earned, cur));
        assertThat(next).as("nuovo = il più alto tra guadagnato e pavimento, mai di più")
                .isLessThanOrEqualTo(Math.max(earned, floor));

        Statistics.label("esito").collect(next == cur ? "retained" : "downgraded");
        Statistics.label("guadagnato vs attuale").collect(earned > cur ? "sopra" : earned == cur ? "uguale" : "sotto");
    }

    /** Il livello guadagnato è il più alto con soglia ≤ punti status (docs/03 §4.3). */
    @Property(tries = 1000)
    void earnedTierIsTheHighestReachedByPeriodSts(@ForAll("scenarios") Scenario s) {
        var res = EditionCloseRule.computeNext(s.current().code(), s.periodSts(), s.scale());
        Tier earned = s.scale().stream().filter(t -> t.code().equals(res.earnedTier())).findFirst().orElseThrow();
        assertThat(earned.thresholdSts()).isLessThanOrEqualTo(s.periodSts());
        assertThat(s.scale()).filteredOn(t -> t.rank() > earned.rank())
                .allMatch(t -> t.thresholdSts() > s.periodSts());
    }

    /** Monotonia: a parità di livello attuale, più punti status non portano mai a un livello più basso. */
    @Property(tries = 1000)
    void moreStatusPointsNeverLowerTheNewTier(@ForAll("scenarios") Scenario s, @ForAll("extraPoints") long extra) {
        var lower = EditionCloseRule.computeNext(s.current().code(), s.periodSts(), s.scale());
        var higher = EditionCloseRule.computeNext(s.current().code(), s.periodSts() + extra, s.scale());
        assertThat(rank(s, higher.newTier())).isGreaterThanOrEqualTo(rank(s, lower.newTier()));
    }

    /** L'esito dice la verità: RETAINED se e solo se il livello non cambia. */
    @Property(tries = 1000)
    void outcomeMatchesTheTierChange(@ForAll("scenarios") Scenario s) {
        var res = EditionCloseRule.computeNext(s.current().code(), s.periodSts(), s.scale());
        boolean same = res.newTier().equals(s.current().code());
        assertThat(res.outcome()).isEqualTo(same
                ? EditionCloseRule.Outcome.RETAINED : EditionCloseRule.Outcome.DOWNGRADED);
    }

    /** Q-149: un livello assente dalla scala non scende né sale, e l'esito lo segnala. */
    @Property(tries = 1000)
    void unknownCurrentTierIsLeftUntouched(@ForAll("scenarios") Scenario s) {
        var res = EditionCloseRule.computeNext("RETIRED", s.periodSts(), s.scale());
        assertThat(res.outcome()).isEqualTo(EditionCloseRule.Outcome.UNKNOWN_TIER);
        assertThat(res.newTier()).isEqualTo("RETIRED");
    }
}
