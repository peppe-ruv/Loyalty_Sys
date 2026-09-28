package io.loyaltyhub.insight.domain;

import io.loyaltyhub.insight.domain.AuditChainReport.Reason;
import io.loyaltyhub.insight.domain.AuditChainReport.Status;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Verifica di una catena di audit, voce per voce in ordine di {@code seq} (F2-GRC-07, docs/servizi/insight-service.md
 * §5). Logica pura, senza database: {@code AuditChainVerifier} le passa le voci a pagine e raccoglie l'esito.
 * <ol>
 *   <li><strong>Inizio</strong>: la prima voce conservata ha {@code seq} 1 e parte dalla {@link AuditHashChain#GENESIS
 *       genesi}, oppure si aggancia a un'ancora registrata per {@code seq - 1} (tipicamente l'ancora {@code PURGE} della
 *       retention). Voci iniziali mancanti senza ancora = cancellazione fuori dal percorso controllato.</li>
 *   <li><strong>Ogni voce</strong>: numerazione contigua; {@code prev_hash} = hash della precedente; hash del
 *       contenuto ricalcolato uguale a quello memorizzato (salvo voci anonimizzate, contate a parte); hash della voce
 *       ricalcolato uguale a quello memorizzato; ogni ancora della stessa posizione coincide.</li>
 *   <li><strong>Fine</strong>: la testa registrata coincide con l'ultima voce e nessuna ancora punta oltre (coda
 *       troncata).</li>
 * </ol>
 * Al primo errore la verifica del servizio si ferma: le voci successive non hanno più un riferimento affidabile.
 */
public final class AuditChainWalk {

    private final String service;
    private final AuditChainHead head;
    private final TreeMap<Long, List<String>> anchors = new TreeMap<>();

    private long checked;
    private long redacted;
    private int anchorsChecked;
    private Long firstSeq;
    private Long lastSeq;
    private String lastHash;
    private AuditChainReport broken;

    /**
     * @param head    testa registrata della catena; {@code null} se manca
     * @param anchors tutte le ancore del servizio (anche quelle di voci già cancellate dalla retention)
     */
    public AuditChainWalk(String service, AuditChainHead head, Collection<AuditAnchor> anchors) {
        this.service = service;
        this.head = head;
        for (AuditAnchor a : anchors) {
            this.anchors.computeIfAbsent(a.seq(), k -> new ArrayList<>()).add(a.entryHash());
        }
    }

    /**
     * Esamina la voce successiva (in ordine di {@code seq} crescente).
     *
     * @return {@code true} se la verifica prosegue, {@code false} se la catena si è già interrotta
     */
    public boolean accept(AuditChainLink link) {
        if (broken != null) {
            return false;
        }
        checked++;
        long seq = link.seq();
        if (firstSeq == null) {
            firstSeq = seq;
            if (!checkStart(link)) {
                return false;
            }
        } else {
            long expected = lastSeq + 1;
            if (seq != expected) {
                return fail(expected, Reason.MISSING_ENTRY,
                        "manca la voce " + expected + ": dopo la " + lastSeq + " viene la " + seq);
            }
            if (!Objects.equals(link.prevHash(), lastHash)) {
                return fail(seq, Reason.PREV_HASH_MISMATCH,
                        "prev_hash della voce " + seq + " non è l'hash della voce " + lastSeq
                                + " (alterata la voce " + lastSeq + " o il collegamento)");
            }
        }
        String content = AuditHashChain.contentHash(link.summary(), link.beforeJson(), link.afterJson());
        if (!content.equals(link.contentHash())) {
            if (link.redactedAt() == null) {
                return fail(seq, Reason.CONTENT_ALTERED,
                        "sintesi o diff della voce " + seq + " non corrispondono all'hash del contenuto");
            }
            redacted++;
        }
        if (!AuditHashChain.entryHash(link).equals(link.entryHash())) {
            return fail(seq, Reason.ENTRY_ALTERED,
                    "i campi della voce " + seq + " non corrispondono al suo hash");
        }
        for (String anchored : anchors.getOrDefault(seq, List.of())) {
            anchorsChecked++;
            if (!anchored.equals(link.entryHash())) {
                return fail(seq, Reason.ANCHOR_MISMATCH,
                        "l'ancora registrata per la voce " + seq + " ha un hash diverso: catena riscritta dopo l'ancoraggio");
            }
        }
        lastSeq = seq;
        lastHash = link.entryHash();
        return true;
    }

    /** Chiude la verifica: confronto con la testa e con le ancore oltre l'ultima voce. */
    public AuditChainReport finish() {
        if (broken != null) {
            return broken;
        }
        if (lastSeq == null) {
            return finishEmpty();
        }
        if (head == null) {
            return report(lastSeq, Reason.HEAD_MISMATCH, "la testa della catena manca");
        }
        if (head.seq() > lastSeq) {
            return report(lastSeq + 1, Reason.TAIL_MISSING,
                    "la testa è alla voce " + head.seq() + " ma l'ultima presente è la " + lastSeq);
        }
        if (head.seq() < lastSeq || !head.entryHash().equals(lastHash)) {
            return report(lastSeq, Reason.HEAD_MISMATCH,
                    "la testa (voce " + head.seq() + ") non coincide con l'ultima voce " + lastSeq);
        }
        if (!anchors.isEmpty() && anchors.lastKey() > lastSeq) {
            return report(lastSeq + 1, Reason.TAIL_MISSING,
                    "un'ancora registra la voce " + anchors.lastKey() + " ma l'ultima presente è la " + lastSeq);
        }
        return new AuditChainReport(service, Status.OK, checked, firstSeq, lastSeq, head.seq(), redacted,
                anchorsChecked, null, null, null);
    }

    /** Nessuna voce conservata: legittimo solo se tutto è stato cancellato dalla retention (ancora sulla testa). */
    private AuditChainReport finishEmpty() {
        long headSeq = head == null ? 0 : head.seq();
        if (headSeq > 0) {
            List<String> atHead = anchors.getOrDefault(headSeq, List.of());
            if (atHead.isEmpty()) {
                return report(headSeq, Reason.TAIL_MISSING,
                        "nessuna voce conservata e nessuna ancora di retention per la testa (voce " + headSeq + ")");
            }
            for (String anchored : atHead) {
                anchorsChecked++;
                if (!anchored.equals(head.entryHash())) {
                    return report(headSeq, Reason.HEAD_MISMATCH,
                            "la testa non coincide con l'ancora registrata per la voce " + headSeq);
                }
            }
        }
        if (!anchors.isEmpty() && anchors.lastKey() > headSeq) {
            return report(headSeq + 1, Reason.TAIL_MISSING,
                    "un'ancora registra la voce " + anchors.lastKey() + " oltre la testa (voce " + headSeq + ")");
        }
        return new AuditChainReport(service, Status.OK, checked, null, null, head == null ? null : head.seq(),
                redacted, anchorsChecked, null, null, null);
    }

    private boolean checkStart(AuditChainLink link) {
        long seq = link.seq();
        if (seq <= 1) {
            if (!AuditHashChain.GENESIS.equals(link.prevHash())) {
                return fail(seq, Reason.GENESIS_MISMATCH, "la voce " + seq + " non parte dalla genesi");
            }
            return true;
        }
        List<String> start = anchors.getOrDefault(seq - 1, List.of());
        if (start.isEmpty()) {
            return fail(seq, Reason.UNANCHORED_START,
                    "le voci fino alla " + (seq - 1) + " mancano e nessuna ancora di retention lo giustifica");
        }
        for (String anchored : start) {
            anchorsChecked++;
            if (!anchored.equals(link.prevHash())) {
                return fail(seq, Reason.PREV_HASH_MISMATCH,
                        "la voce " + seq + " non si aggancia all'ancora registrata per la voce " + (seq - 1));
            }
        }
        return true;
    }

    private boolean fail(long seq, Reason reason, String detail) {
        broken = report(seq, reason, detail);
        return false;
    }

    private AuditChainReport report(long seq, Reason reason, String detail) {
        return new AuditChainReport(service, Status.BROKEN, checked, firstSeq, lastSeq,
                head == null ? null : head.seq(), redacted, anchorsChecked, seq, reason, detail);
    }
}
