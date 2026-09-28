package io.loyaltyhub.insight.domain;

/**
 * Esito della verifica della catena di un servizio ({@code GET /v1/audit/verify}, F2-GRC-07). Con {@code BROKEN},
 * {@code brokenSeq} è la prima posizione in cui la catena non regge e {@code reason} dice perché; la verifica di quel
 * servizio si ferma lì (oltre, i confronti non hanno più un riferimento affidabile).
 *
 * @param service        catena verificata
 * @param status         {@code OK} o {@code BROKEN}
 * @param checked        voci esaminate
 * @param firstSeq       prima voce conservata (dopo la retention può essere &gt; 1); {@code null} se nessuna voce
 * @param lastSeq        ultima voce esaminata; {@code null} se nessuna voce
 * @param headSeq        {@code seq} della testa registrata; {@code null} se la testa manca
 * @param redacted       voci con il contenuto riscritto dall'anonimizzazione (catena valida, contenuto non più
 *                       verificabile contro l'hash originale: {@code SPEC-GAP: Q-401})
 * @param anchorsChecked ancore confrontate con la catena (compresa quella a cui si aggancia la prima voce)
 * @param brokenSeq      prima posizione non valida (solo {@code BROKEN})
 * @param reason         motivo (solo {@code BROKEN})
 * @param detail         spiegazione leggibile (solo {@code BROKEN})
 */
public record AuditChainReport(
        String service,
        Status status,
        long checked,
        Long firstSeq,
        Long lastSeq,
        Long headSeq,
        long redacted,
        int anchorsChecked,
        Long brokenSeq,
        Reason reason,
        String detail) {

    public enum Status { OK, BROKEN }

    /** Perché una catena non regge. */
    public enum Reason {
        /** La prima voce conservata ha {@code seq} 1 ma non parte dalla genesi. */
        GENESIS_MISMATCH,
        /** Mancano voci iniziali senza un'ancora che lo giustifichi (cancellazione fuori dalla retention). */
        UNANCHORED_START,
        /** Manca una voce in mezzo alla catena (numerazione non contigua). */
        MISSING_ENTRY,
        /** {@code prev_hash} non è l'hash della voce precedente (o dell'ancora a cui la catena si aggancia). */
        PREV_HASH_MISMATCH,
        /** Sintesi o diff alterati: l'hash del contenuto non torna e la voce non è stata anonimizzata. */
        CONTENT_ALTERED,
        /** Un campo della voce (attore, oggetto, azione, istante…) o il suo hash sono stati alterati. */
        ENTRY_ALTERED,
        /** Un'ancora registrata non coincide con la voce della stessa posizione. */
        ANCHOR_MISMATCH,
        /** Mancano le ultime voci: la testa o un'ancora puntano oltre l'ultima voce presente. */
        TAIL_MISSING,
        /** La testa registrata non coincide con l'ultima voce. */
        HEAD_MISMATCH
    }

    public boolean ok() {
        return status == Status.OK;
    }
}
