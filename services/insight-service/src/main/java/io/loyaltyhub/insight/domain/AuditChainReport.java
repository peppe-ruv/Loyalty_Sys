package io.loyaltyhub.insight.domain;

import java.time.Instant;

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
 * @param redacted       voci con il contenuto riscritto dall'anonimizzazione e una prova REDACT che lo conferma (o una
 *                       prova già cancellata dalla retention): catena valida
 * @param anchorsChecked ancore confrontate con la catena (compresa quella a cui si aggancia la prima voce)
 * @param brokenSeq      prima posizione non valida (solo {@code BROKEN})
 * @param reason         motivo (solo {@code BROKEN})
 * @param detail         spiegazione leggibile (solo {@code BROKEN})
 * @param expectedAnchor esito del confronto con l'ancora indicata nella richiesta (copiata dai log); {@code null} se
 *                       non indicata
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
        String detail,
        ExpectedAnchor expectedAnchor) {

    public enum Status { OK, BROKEN }

    /** Perché una catena non regge. */
    public enum Reason {
        /** La prima voce conservata ha {@code seq} 1 ma non parte dalla genesi. */
        GENESIS_MISMATCH,
        /** Mancano voci iniziali senza un'ancora PURGE che lo giustifichi (cancellazione fuori dalla retention). */
        UNANCHORED_START,
        /** Manca una voce in mezzo alla catena (numerazione non contigua). */
        MISSING_ENTRY,
        /** {@code prev_hash} non è l'hash della voce precedente (o dell'ancora a cui la catena si aggancia). */
        PREV_HASH_MISMATCH,
        /** Sintesi o diff alterati: l'hash del contenuto non torna né con l'inserimento né con la prova REDACT. */
        CONTENT_ALTERED,
        /** Contenuto riscritto (voce marcata anonimizzata) senza una prova REDACT che lo registri. */
        REDACTION_UNRECORDED,
        /** Un campo della voce (attore, oggetto, azione, istante…) o il suo hash sono stati alterati. */
        ENTRY_ALTERED,
        /** Un'ancora (registrata o indicata) non coincide con la voce della stessa posizione. */
        ANCHOR_MISMATCH,
        /** Mancano le ultime voci: la testa o un'ancora puntano oltre l'ultima voce presente. */
        TAIL_MISSING,
        /** La testa registrata non coincide con l'ultima voce. */
        HEAD_MISMATCH
    }

    /**
     * Confronto con un'ancora indicata nella richiesta (per esempio copiata dalla riga {@code audit-anchor} dei log).
     *
     * @param seq       posizione indicata
     * @param entryHash hash indicato
     * @param result    {@code MATCH}: la catena contiene quella voce con quell'hash; {@code MISMATCH}: la voce (o il
     *                  collegamento con la successiva) ha un altro hash; {@code MISSING}: la catena non arriva più a
     *                  quella posizione; {@code PURGED}: la voce è stata cancellata dalla retention e non si può
     *                  confrontare ({@code purgedAt} dice quando); {@code UNCHECKED}: la catena si interrompe prima
     * @param purgedAt  quando la retention ha cancellato la voce (ancora PURGE che la copre); solo con {@code PURGED}
     */
    public record ExpectedAnchor(long seq, String entryHash, Result result, Instant purgedAt) {

        public enum Result { MATCH, MISMATCH, MISSING, PURGED, UNCHECKED }
    }

    public boolean ok() {
        return status == Status.OK;
    }
}
