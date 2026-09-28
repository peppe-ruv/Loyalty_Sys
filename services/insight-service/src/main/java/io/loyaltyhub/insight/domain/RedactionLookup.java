package io.loyaltyhub.insight.domain;

import java.time.Instant;
import java.util.Optional;

/** Dove {@link AuditChainWalk} cerca le prove delle anonimizzazioni (voci REDACT della catena {@code insight}). */
public interface RedactionLookup {

    /** Ultima prova REDACT per la voce {@code entryId}, se è ancora conservata. */
    Optional<RedactionEvidence> latest(String entryId);

    /**
     * Da quando le prove sono conservate: l'istante della prima voce rimasta nella catena {@code insight}
     * ({@link Instant#MAX} se la retention l'ha svuotata tutta). Vuoto se la catena {@code insight} non esiste: nessuna
     * prova è mai stata scritta. Una prova mancante è accettabile solo se l'anonimizzazione è precedente a questo
     * istante (la retention l'ha cancellata insieme alle voci più vecchie).
     */
    Optional<Instant> retainedFrom();

    /** Nessuna prova disponibile (catena {@code insight} inesistente). */
    RedactionLookup NONE = new RedactionLookup() {
        @Override
        public Optional<RedactionEvidence> latest(String entryId) {
            return Optional.empty();
        }

        @Override
        public Optional<Instant> retainedFrom() {
            return Optional.empty();
        }
    };
}
