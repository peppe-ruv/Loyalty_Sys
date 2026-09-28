package io.loyaltyhub.insight.domain;

import io.loyaltyhub.insight.domain.AuditChainReport.ExpectedAnchor;
import io.loyaltyhub.insight.domain.AuditChainReport.Reason;
import io.loyaltyhub.insight.domain.AuditChainReport.Status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Verifica di una catena di audit, voce per voce in ordine di {@code seq} (F2-GRC-07, docs/servizi/insight-service.md
 * §5). Logica pura, senza database: {@code AuditChainVerifier} le passa le voci a pagine e raccoglie l'esito.
 * <ol>
 *   <li><strong>Inizio</strong>: la prima voce conservata ha {@code seq} 1 e parte dalla {@link AuditHashChain#GENESIS
 *       genesi}, oppure si aggancia a un'ancora {@code PURGE} di {@code seq - 1} (la retention). Voci iniziali mancanti
 *       senza ancora PURGE = cancellazione fuori dalla retention; un'ancora di altro tipo non basta.</li>
 *   <li><strong>Ogni voce</strong>: numerazione contigua; {@code prev_hash} = hash della precedente; hash del contenuto
 *       ricalcolato uguale a quello dell'inserimento oppure, per una voce anonimizzata, a quello registrato dalla sua
 *       prova REDACT (integra e riferita a questa voce); hash della voce ricalcolato uguale a quello memorizzato; ogni
 *       ancora della stessa posizione coincide.</li>
 *   <li><strong>Fine</strong>: la testa registrata coincide con l'ultima voce e nessuna ancora punta oltre (coda
 *       troncata). Una catena senza voci è legittima solo se un'ancora PURGE copre la testa.</li>
 * </ol>
 * Un'ancora {@link AuditAnchor#EXTERNAL} (copiata dai log) si confronta come le altre, e il suo esito è riportato a
 * parte ({@link ExpectedAnchor}). Al primo errore la verifica del servizio si ferma.
 */
public final class AuditChainWalk {

    private final String service;
    private final AuditChainHead head;
    private final RedactionLookup redactions;
    private final AuditAnchor expected;
    private final TreeMap<Long, List<AuditAnchor>> anchors = new TreeMap<>();

    private long checked;
    private long redacted;
    private int anchorsChecked;
    private Long firstSeq;
    private Long lastSeq;
    private String lastHash;
    private ExpectedAnchor.Result expectedOutcome;
    private AuditAnchor segmentStart;
    private AuditChainReport broken;

    /**
     * @param head       testa registrata della catena; {@code null} se manca
     * @param anchors    tutte le ancore registrate del servizio (anche quelle di voci già cancellate dalla retention)
     * @param redactions dove cercare le prove REDACT delle voci anonimizzate
     * @param expected   ancora indicata da chi verifica (tipo {@link AuditAnchor#EXTERNAL}); {@code null} se nessuna
     */
    public AuditChainWalk(String service, AuditChainHead head, Collection<AuditAnchor> anchors,
                          RedactionLookup redactions, AuditAnchor expected) {
        this.service = service;
        this.head = head;
        this.redactions = redactions;
        this.expected = expected;
        for (AuditAnchor a : anchors) {
            this.anchors.computeIfAbsent(a.seq(), k -> new ArrayList<>()).add(a);
        }
        if (expected != null) {
            this.anchors.computeIfAbsent(expected.seq(), k -> new ArrayList<>()).add(expected);
        }
    }

    public AuditChainWalk(String service, AuditChainHead head, Collection<AuditAnchor> anchors) {
        this(service, head, anchors, RedactionLookup.NONE, null);
    }

    /**
     * Verifica solo il tratto successivo a un'ancora già verificata (job di ancoraggio): la voce {@code seq + 1} può
     * agganciarsi a quest'ancora anche se non è di tipo PURGE.
     */
    public AuditChainWalk startingAfter(AuditAnchor verified) {
        this.segmentStart = verified;
        return this;
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
            long expectedSeq = lastSeq + 1;
            if (seq != expectedSeq) {
                return fail(expectedSeq, Reason.MISSING_ENTRY,
                        "manca la voce " + expectedSeq + ": dopo la " + lastSeq + " viene la " + seq);
            }
            if (!Objects.equals(link.prevHash(), lastHash)) {
                return fail(seq, Reason.PREV_HASH_MISMATCH,
                        "prev_hash della voce " + seq + " non è l'hash della voce " + lastSeq
                                + " (alterata la voce " + lastSeq + " o il collegamento)");
            }
        }
        if (!checkContent(link)) {
            return false;
        }
        if (!AuditHashChain.entryHash(link).equals(link.entryHash())) {
            return fail(seq, Reason.ENTRY_ALTERED, "i campi della voce " + seq + " non corrispondono al suo hash");
        }
        if (!compareAnchors(seq, link.entryHash(), Reason.ANCHOR_MISMATCH)) {
            return false;
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
        AuditChainReport beyond = anchorsBeyond(lastSeq);
        return beyond != null ? beyond : ok();
    }

    /** Nessuna voce conservata: legittimo solo se la retention ha cancellato tutto (ancora PURGE sulla testa). */
    private AuditChainReport finishEmpty() {
        long headSeq = head == null ? 0 : head.seq();
        if (headSeq > 0) {
            if (!compareAnchors(headSeq, head.entryHash(), Reason.HEAD_MISMATCH)) {
                return broken;
            }
            if (anchors.getOrDefault(headSeq, List.of()).stream().noneMatch(AuditAnchor::isPurge)) {
                return report(headSeq, Reason.TAIL_MISSING,
                        "nessuna voce conservata e nessuna ancora di retention per la testa (voce " + headSeq + ")");
            }
        }
        AuditChainReport beyond = anchorsBeyond(headSeq);
        return beyond != null ? beyond : ok();
    }

    private boolean checkStart(AuditChainLink link) {
        long seq = link.seq();
        if (seq <= 1) {
            if (!AuditHashChain.GENESIS.equals(link.prevHash())) {
                return fail(seq, Reason.GENESIS_MISMATCH, "la voce " + seq + " non parte dalla genesi");
            }
            return true;
        }
        if (!compareAnchors(seq - 1, link.prevHash(), Reason.PREV_HASH_MISMATCH)) {
            return false;
        }
        if (segmentStart != null && segmentStart.seq() == seq - 1) {
            if (!segmentStart.entryHash().equals(link.prevHash())) {
                return fail(seq, Reason.PREV_HASH_MISMATCH,
                        "la voce " + seq + " non si aggancia all'ultima ancora verificata (voce " + (seq - 1) + ")");
            }
            return true;
        }
        if (anchors.getOrDefault(seq - 1, List.of()).stream().noneMatch(AuditAnchor::isPurge)) {
            return fail(seq, Reason.UNANCHORED_START,
                    "le voci fino alla " + (seq - 1) + " mancano e nessuna ancora di retention (PURGE) lo giustifica");
        }
        return true;
    }

    /** Contenuto: uguale all'inserimento, oppure riscritto dall'anonimizzazione con una prova REDACT coerente. */
    private boolean checkContent(AuditChainLink link) {
        long seq = link.seq();
        String content = AuditHashChain.contentHash(link.summary(), link.beforeJson(), link.afterJson());
        if (content.equals(link.contentHash())) {
            return true;
        }
        if (link.redactedAt() == null) {
            return fail(seq, Reason.CONTENT_ALTERED,
                    "sintesi o diff della voce " + seq + " non corrispondono all'hash del contenuto");
        }
        Optional<RedactionEvidence> evidence = redactions.latest(link.id());
        if (evidence.isPresent()) {
            RedactionEvidence e = evidence.get();
            if (!e.intact()) {
                return fail(seq, Reason.CONTENT_ALTERED,
                        "la prova di anonimizzazione della voce " + seq + " (insight n. " + e.link().seq() + ") è alterata");
            }
            if (!service.equals(e.targetService()) || e.targetSeq() == null || e.targetSeq() != seq) {
                return fail(seq, Reason.REDACTION_UNRECORDED,
                        "la prova di anonimizzazione trovata per la voce " + seq + " riguarda un'altra voce");
            }
            if (!content.equals(e.contentHash())) {
                return fail(seq, Reason.CONTENT_ALTERED, "il contenuto della voce " + seq
                        + " non è quello registrato dalla sua anonimizzazione (insight n. " + e.link().seq() + ")");
            }
        } else {
            Optional<Instant> retainedFrom = redactions.retainedFrom();
            if (retainedFrom.isEmpty() || !link.redactedAt().isBefore(retainedFrom.get())) {
                return fail(seq, Reason.REDACTION_UNRECORDED,
                        "il contenuto della voce " + seq + " è stato riscritto senza una prova di anonimizzazione");
            }
            // Prova più vecchia della prima voce rimasta della catena insight: cancellata dalla retention.
        }
        redacted++;
        return true;
    }

    /** Confronta le ancore della posizione {@code seq} con {@code hash}; un'ancora esterna ha un esito a parte. */
    private boolean compareAnchors(long seq, String hash, Reason onMismatch) {
        for (AuditAnchor a : anchors.getOrDefault(seq, List.of())) {
            boolean match = a.entryHash().equals(hash);
            if (a.isExternal()) {
                expectedOutcome = match ? ExpectedAnchor.Result.MATCH : ExpectedAnchor.Result.MISMATCH;
                if (!match) {
                    return fail(seq, Reason.ANCHOR_MISMATCH,
                            "l'ancora indicata per la voce " + seq + " ha un hash diverso da quello della catena");
                }
                continue;
            }
            anchorsChecked++;
            if (!match) {
                return fail(onMismatch == Reason.PREV_HASH_MISMATCH ? seq + 1 : seq, onMismatch,
                        "l'ancora " + a.kind() + " registrata per la voce " + seq + " ha un hash diverso: catena "
                                + "riscritta dopo l'ancoraggio");
            }
        }
        return true;
    }

    /** Un'ancora oltre l'ultima posizione presente: voci finali cancellate. */
    private AuditChainReport anchorsBeyond(long last) {
        if (anchors.isEmpty() || anchors.lastKey() <= last) {
            return null;
        }
        long beyond = anchors.lastKey();
        boolean onlyExternal = anchors.get(beyond).stream().allMatch(AuditAnchor::isExternal);
        if (expected != null && expected.seq() > last) {
            expectedOutcome = ExpectedAnchor.Result.MISSING;
        }
        return report(last + 1, Reason.TAIL_MISSING, (onlyExternal ? "l'ancora indicata" : "un'ancora registrata")
                + " riguarda la voce " + beyond + " ma la catena arriva alla " + last);
    }

    private boolean fail(long seq, Reason reason, String detail) {
        broken = report(seq, reason, detail);
        return false;
    }

    private AuditChainReport ok() {
        return new AuditChainReport(service, Status.OK, checked, firstSeq, lastSeq, head == null ? null : head.seq(),
                redacted, anchorsChecked, null, null, null, expectedResult());
    }

    private AuditChainReport report(long seq, Reason reason, String detail) {
        return new AuditChainReport(service, Status.BROKEN, checked, firstSeq, lastSeq,
                head == null ? null : head.seq(), redacted, anchorsChecked, seq, reason, detail, expectedResult());
    }

    /** Esito dell'ancora indicata: confrontata, oltre la catena, già cancellata dalla retention o non raggiunta. */
    private ExpectedAnchor expectedResult() {
        if (expected == null) {
            return null;
        }
        ExpectedAnchor.Result result = expectedOutcome;
        if (result == null) {
            long retainedStart = firstSeq != null ? firstSeq : (head == null ? 1 : head.seq() + 1);
            result = expected.seq() < retainedStart - 1 || (firstSeq == null && expected.seq() < retainedStart)
                    ? ExpectedAnchor.Result.PURGED : ExpectedAnchor.Result.UNCHECKED;
        }
        Instant purgedAt = null;
        if (result == ExpectedAnchor.Result.PURGED) {
            purgedAt = anchors.tailMap(expected.seq(), true).values().stream().flatMap(List::stream)
                    .filter(AuditAnchor::isPurge).map(AuditAnchor::anchoredAt).filter(Objects::nonNull)
                    .findFirst().orElse(null);
        }
        return new ExpectedAnchor(expected.seq(), expected.entryHash(), result, purgedAt);
    }
}
