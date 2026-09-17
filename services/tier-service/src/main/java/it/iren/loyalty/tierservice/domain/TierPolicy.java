package it.iren.loyalty.tierservice.domain;

import java.util.List;

/**
 * Politica dei tier (D06, RF-10..RF-12): soglie sui punti STATUS dell'anno programma, upgrade immediato,
 * a fine anno discesa di un solo livello per chi non conferma la soglia. Le soglie arrivano dal backoffice.
 */
public record TierPolicy(List<Tier> tiers) {
    public record Tier(String code, int order, long statusThreshold) {}

    public TierPolicy {
        tiers = tiers.stream().sorted((a, b) -> Integer.compare(a.order(), b.order())).toList();
        if (tiers.isEmpty() || tiers.get(0).statusThreshold() != 0) throw new IllegalArgumentException("first tier must have threshold 0");
    }

    /** Tier raggiunto con i punti status dell'anno. */
    public Tier qualified(long statusPoints) {
        Tier best = tiers.get(0);
        for (Tier t : tiers) if (statusPoints >= t.statusThreshold()) best = t;
        return best;
    }

    /** Upgrade immediato (RF-10): il tier corrente sale se i punti superano la soglia; non scende mai in corso d'anno. */
    public Tier duringYear(Tier current, long statusPoints) {
        Tier q = qualified(statusPoints);
        return q.order() > current.order() ? q : current;
    }

    /** Verifica annuale (RF-11): il tier dell'anno nuovo è quello qualificato, ma al massimo un livello sotto l'attuale. */
    public Tier atYearEnd(Tier current, long statusPointsThisYear) {
        Tier q = qualified(statusPointsThisYear);
        if (q.order() >= current.order()) return q;
        int floor = Math.max(0, current.order() - 1);
        return tiers.stream().filter(t -> t.order() == floor).findFirst().orElse(tiers.get(0));
    }

    /** Tier con questo codice, se esiste: da usare quando il codice arriva da fuori (assegnazione manuale). */
    public java.util.Optional<Tier> find(String code) {
        return tiers.stream().filter(t -> t.code().equals(code)).findFirst();
    }

    /** Tier con questo codice, con ripiego sul livello base: per i percorsi di lettura, che non devono fallire. */
    public Tier byCode(String code) {
        return find(code).orElse(tiers.get(0));
    }

    /** Soglie di esempio, da tarare sui dati reali (punto aperto). */
    public static TierPolicy example() {
        return new TierPolicy(List.of(new Tier("BASE", 0, 0), new Tier("PLUS", 1, 1_500), new Tier("TOP", 2, 4_000)));
    }
}
