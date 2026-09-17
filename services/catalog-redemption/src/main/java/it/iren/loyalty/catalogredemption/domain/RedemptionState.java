package it.iren.loyalty.catalogredemption.domain;

import java.util.Map;
import java.util.Set;

/**
 * Ciclo di vita di riscatto e vincita (RF-16, RF-17, RF-39, RF-76, RF-103), superset degli stati di Open Loyalty
 * (Pending, Approved, Packing, Waiting for shipping, Shipped, Completed, Returned, Rejected, Cancelled, Issued):
 * REQUESTED → CONFIRMED (approvato) → PACKING → WAITING_FOR_SHIPPING → IN_DELIVERY (shipped) → DELIVERED (completed)
 * → USED; RETURNED dopo la consegna; REJECTED dall'operatore prima della consegna con storno; CANCELLED dal cliente;
 * EXPIRED; DONATED (premi non ritirati, ONLUS). Cambi di stato anche massivi (RF-103).
 */
public enum RedemptionState {
    REQUESTED, CONFIRMED, PACKING, WAITING_FOR_SHIPPING, IN_DELIVERY, DELIVERED, USED, RETURNED, REJECTED, CANCELLED, EXPIRED, DONATED;

    private static final Map<RedemptionState, Set<RedemptionState>> NEXT = Map.ofEntries(
            Map.entry(REQUESTED, Set.of(CONFIRMED, REJECTED, CANCELLED)),
            Map.entry(CONFIRMED, Set.of(PACKING, WAITING_FOR_SHIPPING, IN_DELIVERY, DELIVERED, USED, REJECTED, CANCELLED, EXPIRED)),
            Map.entry(PACKING, Set.of(WAITING_FOR_SHIPPING, IN_DELIVERY, REJECTED)),
            Map.entry(WAITING_FOR_SHIPPING, Set.of(IN_DELIVERY, REJECTED)),
            Map.entry(IN_DELIVERY, Set.of(DELIVERED, RETURNED, EXPIRED)),
            Map.entry(DELIVERED, Set.of(USED, RETURNED, EXPIRED, DONATED)),
            Map.entry(USED, Set.of()), Map.entry(RETURNED, Set.of(CONFIRMED)), Map.entry(REJECTED, Set.of()), Map.entry(CANCELLED, Set.of()),
            Map.entry(EXPIRED, Set.of(DONATED)), Map.entry(DONATED, Set.of()));

    public boolean canGo(RedemptionState to) { return NEXT.getOrDefault(this, Set.of()).contains(to); }
    /** Annullabile dal cliente finché il premio non è in lavorazione (RF-17). */
    public boolean cancellableByMember() { return this == REQUESTED || this == CONFIRMED; }
    /** Stati che restituiscono i punti (RF-17, RF-103). */
    public boolean refunds() { return this == CANCELLED || this == REJECTED || this == RETURNED; }
}
