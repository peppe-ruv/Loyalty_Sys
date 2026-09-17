package io.loyaltyhub.ingressadapters.schema;

import java.util.List;
import java.util.Optional;

/** Porta verso il backoffice: schemi pubblicati per tipo azione (RF-98). Senza schema per un tipo: {@code Optional.empty()} e l'ingresso applica la politica configurata. */
public interface SchemaRegistry {
    Optional<EventSchema> byActionType(String actionType);
    List<EventSchema> all();
    /** true = un tipo senza schema è rifiutato (RF-01: il tipo azione è un catalogo). */
    default boolean rejectUnknownTypes() { return false; }
}
