package io.loyaltyhub.insight.domain;

import java.time.Instant;
import java.util.Optional;

/** Dove {@link AuditChainWalk} cerca le prove delle anonimizzazioni (voci REDACT della catena {@code audit.redaction}). */
public interface RedactionLookup {

    /** Ultima prova REDACT per la voce {@code entryId}, se è ancora conservata. */
    Optional<RedactionEvidence> latest(String entryId);

    /**
     * Da quando la retention non può aver cancellato le prove: {@code now() - audit_min_retention_days()}. Una prova
     * mancante è accettabile solo per un'anonimizzazione più vecchia di questo istante; più recente, qualcuno l'ha tolta.
     */
    Instant evidenceKeptSince();

    /** Nessuna prova disponibile e nessuna tolleranza: ogni voce riscritta senza prova è un'interruzione. */
    RedactionLookup NONE = new RedactionLookup() {
        @Override
        public Optional<RedactionEvidence> latest(String entryId) {
            return Optional.empty();
        }

        @Override
        public Instant evidenceKeptSince() {
            return Instant.MIN;
        }
    };
}
