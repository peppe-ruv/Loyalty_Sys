package io.loyaltyhub.insight.domain;

/**
 * Prova di un'anonimizzazione: la voce REDACT che il database accoda nella catena riservata {@code audit.redaction} ogni
 * volta che il contenuto di una voce di audit viene riscritto (V6, F2-GRC-07, SPEC-GAP: Q-401). Porta la voce riscritta
 * ({@code targetService}, {@code targetSeq}), l'hash del contenuto dopo la riscrittura e, se la riscrittura è passata da
 * {@code audit_redact}, il membro anonimizzato e il fatto di anonimizzazione.
 *
 * @param link          la voce REDACT così come è memorizzata (per ricalcolarne gli hash)
 * @param targetService servizio della voce riscritta ({@code after.service})
 * @param targetSeq     posizione della voce riscritta ({@code after.seq}); {@code null} se assente o non numerica
 * @param contentHash   hash del contenuto dopo la riscrittura ({@code after.contentHash})
 * @param memberId      membro anonimizzato ({@code after.memberId}); {@code null} se non indicato
 */
public record RedactionEvidence(AuditChainLink link, String targetService, Long targetSeq, String contentHash,
                                String memberId) {

    /** La voce REDACT è integra: i suoi hash tornano con il suo contenuto e i suoi campi. */
    public boolean intact() {
        return AuditHashChain.contentHash(link.summary(), link.beforeJson(), link.afterJson()).equals(link.contentHash())
                && AuditHashChain.entryHash(link).equals(link.entryHash());
    }
}
