package io.loyaltyhub.contestservice.wheel;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;

/**
 * Ruota della fortuna (RF-95): spicchi con premio, peso di probabilità e spicchi vuoti; costo del giro in unità di un
 * wallet; giri per membro nel periodo; date di attività; limiti di stock e budget e vincite per membro, verificati dopo
 * l'estrazione (livello di business logic). Ogni giro è indipendente dal precedente.
 *
 * <p>Vincolo italiano (DPR 430/2001): se la vincita dipende dalla sorte, la ruota è un concorso a premi e deve
 * essere alimentata dagli istanti vincenti periziati ({@link Mode#INSTANT_WIN_BACKED}: lo spicchio "vincente" esce solo
 * se la giocata cade dopo un istante vincente non assegnato, altrimenti esce uno spicchio non vincente); la modalità
 * {@link Mode#PROBABILITY} è ammessa solo con premi di valore nullo (sconti, punti) o fuori Italia.
 */
public record FortuneWheel(String id, String name, boolean active, Mode mode, String contestId, List<Slot> slots, String costWallet, long costUnits,
                           int spinsPerMember, io.loyaltyhub.contestservice.wheel.Period spinsPeriod, Instant startsAt, Instant endsAt,
                           long budgetUnits, int maxWinsPerMemberPerDay, String visibility) {
    public enum Mode { PROBABILITY, INSTANT_WIN_BACKED }
    /** Spicchio: premio (id catalogo) o unità su un wallet, oppure vuoto; {@code weight} = probabilità relativa; {@code stock} (-1 illimitato). */
    public record Slot(String id, String label, int weight, String rewardId, String wallet, long units, long stock, boolean winning) {
        public boolean isEmpty() { return rewardId == null && units == 0; }
    }
    public record Spin(String slotId, boolean won, String rewardId, String wallet, long units) {}

    public FortuneWheel {
        if (slots == null || slots.isEmpty()) throw new IllegalArgumentException("wheel needs slots");
        if (slots.stream().mapToInt(Slot::weight).sum() <= 0) throw new IllegalArgumentException("weights must sum > 0");
    }

    public boolean isActiveAt(Instant t) { return active && (startsAt == null || !t.isBefore(startsAt)) && (endsAt == null || t.isBefore(endsAt)); }

    /** Estrazione pesata tra gli spicchi ammessi ({@code eligible}: stock > 0 e, in modalità instant win, coerenti con l'esito). */
    public Slot draw(SecureRandom rnd, List<Slot> eligible) {
        int total = eligible.stream().mapToInt(Slot::weight).sum();
        if (total <= 0) return null;
        int r = rnd.nextInt(total);
        for (Slot s : eligible) { r -= s.weight(); if (r < 0) return s; }
        return eligible.get(eligible.size() - 1);
    }

    /** Spicchi ammessi: stock disponibile; in modalità instant win solo vincenti se {@code instantWon}, altrimenti solo non vincenti. */
    public List<Slot> eligible(boolean instantWon) {
        return slots.stream().filter(s -> s.stock() != 0).filter(s -> mode == Mode.PROBABILITY || s.winning() == instantWon).toList();
    }

    /** Probabilità teorica di ogni spicchio (per il backoffice e la perizia), sul set ammesso. */
    public double probabilityOf(Slot slot, List<Slot> eligible) {
        int total = eligible.stream().mapToInt(Slot::weight).sum();
        return total == 0 || !eligible.contains(slot) ? 0 : (double) slot.weight() / total;
    }
}
