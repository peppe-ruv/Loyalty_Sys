package it.iren.loyalty.catalogredemption.domain;

import java.util.Map;
import java.util.Set;

/**
 * Ciclo di vita di riscatto e vincita (RF-16, RF-17, RF-39, RF-76). USED = buono/codice segnato come utilizzato
 * (dall'operatore di sportello o dal sistema partner), equivalente del "mark as used" di Open Loyalty.
 */
public enum RedemptionState {
    REQUESTED, CONFIRMED, CANCELLED, IN_DELIVERY, DELIVERED, USED, EXPIRED, DONATED;

    private static final Map<RedemptionState, Set<RedemptionState>> NEXT = Map.of(
            REQUESTED, Set.of(CONFIRMED, CANCELLED),
            CONFIRMED, Set.of(CANCELLED, IN_DELIVERY, DELIVERED, USED, EXPIRED),
            IN_DELIVERY, Set.of(DELIVERED, EXPIRED),
            DELIVERED, Set.of(USED, EXPIRED, DONATED),
            USED, Set.of(), CANCELLED, Set.of(), EXPIRED, Set.of(DONATED), DONATED, Set.of());

    public boolean canGo(RedemptionState to) { return NEXT.getOrDefault(this, Set.of()).contains(to); }
    /** Annullabile dal cliente finché il premio non è in consegna né usato (RF-17). */
    public boolean cancellableByMember() { return this == REQUESTED || this == CONFIRMED; }
}
