package it.iren.loyalty.segmentservice.domain;

import java.util.Optional;
import java.util.stream.Stream;

/** Porta verso il read-model: snapshot dei membri per il ricalcolo (a lotti) e per la valutazione puntuale. */
public interface SnapshotSource {
    Optional<MemberSnapshot> snapshotOf(String memberId);
    /** Tutti i membri attivi, in streaming: il ricalcolo completo non carica 2 milioni di profili in memoria. */
    Stream<MemberSnapshot> allActive();
}
