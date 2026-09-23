package io.loyaltyhub.wallet.domain;

import java.util.Comparator;
import java.util.List;

/** Regole di chiusura edizione (docs/03 §4.3, M3.4). */
public final class EditionCloseRule {

    private EditionCloseRule() {
    }

    public enum Outcome { RETAINED, DOWNGRADED }

    public record Result(String earnedTier, String newTier, Outcome outcome) { }

    /**
     * Calcola il nuovo livello in chiusura.
     * @param currentCode livello attuale del membro
     * @param periodSts punti accumulati nell'edizione
     * @param scale scala dei livelli, ordinata per rank crescente
     */
    public static Result computeNext(String currentCode, long periodSts, List<Tier> scale) {
        Tier current = scale.stream().filter(t -> t.code().equals(currentCode)).findFirst()
                .orElse(scale.isEmpty() ? null : scale.get(0));

        if (current == null) {
             throw new IllegalArgumentException("Scala dei livelli vuota o livello corrente non trovato");
        }

        Tier earned = scale.stream()
                .filter(t -> t.thresholdSts() <= periodSts)
                .max(Comparator.comparingInt(Tier::rank))
                .orElse(scale.get(0));

        int floorRank = Math.max(0, current.rank() - 1);
        Tier floor = scale.stream().filter(t -> t.rank() == floorRank).findFirst().orElse(scale.get(0));

        Tier newTier = (earned.rank() >= floor.rank()) ? earned : floor;

        // "La salita non avviene mai in chiusura"
        if (newTier.rank() > current.rank()) {
             newTier = current;
        }

        Outcome outcome = newTier.code().equals(current.code()) ? Outcome.RETAINED : Outcome.DOWNGRADED;
        return new Result(earned.code(), newTier.code(), outcome);
    }
}
