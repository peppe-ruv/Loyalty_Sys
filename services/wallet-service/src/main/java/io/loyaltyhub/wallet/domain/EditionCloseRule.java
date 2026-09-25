package io.loyaltyhub.wallet.domain;

import java.util.Comparator;
import java.util.List;

/** Regole di chiusura edizione (docs/03 §4.3, M3.4). */
public final class EditionCloseRule {

    private EditionCloseRule() {
    }

    /**
     * Esito per un membro. {@code UNKNOWN_TIER} (Q-149 DECISA): il livello attuale non esiste più nella scala, il
     * membro resta invariato e la chiusura lo segnala nel riepilogo.
     */
    public enum Outcome { RETAINED, DOWNGRADED, UNKNOWN_TIER }

    public record Result(String earnedTier, String newTier, Outcome outcome) { }

    /**
     * Calcola il nuovo livello in chiusura.
     * @param currentCode livello attuale del membro
     * @param periodSts punti accumulati nell'edizione
     * @param scale scala dei livelli, ordinata per rank crescente
     */
    public static Result computeNext(String currentCode, long periodSts, List<Tier> scale) {
        if (scale.isEmpty()) {
            throw new IllegalArgumentException("Scala dei livelli vuota");
        }

        Tier earned = scale.stream()
                .filter(t -> t.thresholdSts() <= periodSts)
                .max(Comparator.comparingInt(Tier::rank))
                .orElse(scale.get(0));

        Tier current = scale.stream().filter(t -> t.code().equals(currentCode)).findFirst().orElse(null);
        if (current == null) {
            // Q-149 DECISA: livello sconosciuto → nessuna discesa non dichiarata; il membro resta com'è.
            return new Result(earned.code(), currentCode, Outcome.UNKNOWN_TIER);
        }

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
