package io.loyaltyhub.insight.domain;

import io.loyaltyhub.insight.domain.AuditChainReport.ExpectedAnchor;
import io.loyaltyhub.insight.domain.AuditChainReport.Reason;
import io.loyaltyhub.insight.domain.AuditChainReport.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regole della verifica di una catena di audit (F2-GRC-07; docs/servizi/insight-service.md §5): inizio dalla genesi o
 * da un'ancora, continuità, contenuto, voce, ancore, testa. Catene costruite in memoria con {@link AuditHashChain}.
 */
class AuditChainWalkTest {

    private static final String SVC = "campaign";
    private static final Instant T0 = Instant.parse("2026-09-01T08:00:00.000001Z");

    /** Catena valida di {@code n} voci a partire da {@code firstSeq} agganciata a {@code prev}. */
    private static List<AuditChainLink> chain(long firstSeq, String prev, int n) {
        List<AuditChainLink> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long seq = firstSeq + i;
            out.add(seal(seq, prev, "Voce " + seq, "{\"n\": " + seq + "}", null));
            prev = out.getLast().entryHash();
        }
        return out;
    }

    private static List<AuditChainLink> chain(int n) {
        return chain(1, AuditHashChain.GENESIS, n);
    }

    /** Voce con hash coerenti (come la scriverebbe il trigger). */
    private static AuditChainLink seal(long seq, String prev, String summary, String before, String actorName) {
        String content = AuditHashChain.contentHash(summary, before, null);
        AuditChainLink l = new AuditChainLink(SVC, seq, prev, "ID-" + seq, "EVT-" + seq, T0.plusSeconds(seq), "ADMIN",
                actorName == null ? "marta.admin" : actorName, "CAMPAIGN", "CMP-" + seq, "UPDATE", "COR-" + seq,
                summary, before, null, content, null, null);
        return withEntryHash(l, AuditHashChain.entryHash(l));
    }

    private static AuditChainLink withEntryHash(AuditChainLink l, String entryHash) {
        return new AuditChainLink(l.service(), l.seq(), l.prevHash(), l.id(), l.eventId(), l.at(), l.actorRole(),
                l.actorName(), l.entityType(), l.entityId(), l.action(), l.correlationId(), l.summary(), l.beforeJson(),
                l.afterJson(), l.contentHash(), entryHash, l.redactedAt());
    }

    private static AuditChainLink withSummary(AuditChainLink l, String summary, Instant redactedAt) {
        return new AuditChainLink(l.service(), l.seq(), l.prevHash(), l.id(), l.eventId(), l.at(), l.actorRole(),
                l.actorName(), l.entityType(), l.entityId(), l.action(), l.correlationId(), summary, l.beforeJson(),
                l.afterJson(), l.contentHash(), l.entryHash(), redactedAt);
    }

    private static AuditChainHead headOf(List<AuditChainLink> links) {
        AuditChainLink last = links.getLast();
        return new AuditChainHead(SVC, last.seq(), last.entryHash());
    }

    private static AuditAnchor anchor(AuditChainLink l, String kind) {
        return new AuditAnchor(SVC, l.seq(), l.entryHash(), kind, Instant.now());
    }

    private static AuditChainReport walk(List<AuditChainLink> links, AuditChainHead head, List<AuditAnchor> anchors) {
        return walk(new AuditChainWalk(SVC, head, anchors), links);
    }

    private static AuditChainReport walk(AuditChainWalk w, List<AuditChainLink> links) {
        for (AuditChainLink l : links) {
            if (!w.accept(l)) {
                break;
            }
        }
        return w.finish();
    }

    private static void assertBroken(AuditChainReport r, long seq, Reason reason) {
        assertThat(r.status()).as(r.detail()).isEqualTo(Status.BROKEN);
        assertThat(r.brokenSeq()).as(r.detail()).isEqualTo(seq);
        assertThat(r.reason()).as(r.detail()).isEqualTo(reason);
        assertThat(r.detail()).isNotBlank();
    }

    @Test
    @DisplayName("catena integra dalla genesi: OK, voci contate, testa coincidente")
    void intact() {
        List<AuditChainLink> c = chain(5);
        AuditChainReport r = walk(c, headOf(c), List.of());
        assertThat(r.ok()).isTrue();
        assertThat(r.checked()).isEqualTo(5);
        assertThat(r.firstSeq()).isEqualTo(1);
        assertThat(r.lastSeq()).isEqualTo(5);
        assertThat(r.headSeq()).isEqualTo(5);
        assertThat(r.brokenSeq()).isNull();
        assertThat(r.reason()).isNull();
    }

    @Test
    @DisplayName("sintesi alterata: CONTENT_ALTERED esattamente su quella voce")
    void contentAltered() {
        List<AuditChainLink> c = chain(5);
        c.set(2, withSummary(c.get(2), "Voce truccata", null));
        assertBroken(walk(c, headOf(c), List.of()), 3, Reason.CONTENT_ALTERED);
    }

    // ---------- anonimizzazione e prove REDACT ----------

    private static final Instant REDACTED_AT = Instant.parse("2026-09-10T12:00:00Z");

    /** Prova REDACT integra (come la scrive il trigger) per la voce {@code target} riscritta in {@code content}. */
    private static RedactionEvidence evidence(AuditChainLink target, String contentHash) {
        String after = "{\"seq\": " + target.seq() + ", \"service\": \"" + target.service() + "\", \"memberId\": \"MBR-1\", "
                + "\"contentHash\": \"" + contentHash + "\"}";
        String summary = "Anonimizzata la voce di audit " + target.service() + " n. " + target.seq();
        AuditChainLink l = new AuditChainLink("audit.redaction", 40, "a".repeat(64), "redact-1", "redact-1", REDACTED_AT,
                "system", "insight", "AUDIT_ENTRY", target.id(), "REDACT", null, summary, null, after,
                AuditHashChain.contentHash(summary, null, after), null, null);
        return new RedactionEvidence(withEntryHash(l, AuditHashChain.entryHash(l)), target.service(), target.seq(),
                contentHash, "MBR-1");
    }

    private static RedactionLookup lookup(Map<String, RedactionEvidence> byEntry, Instant keptSince) {
        return new RedactionLookup() {
            @Override
            public Optional<RedactionEvidence> latest(String entryId) {
                return Optional.ofNullable(byEntry.get(entryId));
            }

            @Override
            public Instant evidenceKeptSince() {
                return keptSince;
            }
        };
    }

    private static List<AuditChainLink> redactedChain() {
        List<AuditChainLink> c = chain(5);
        c.set(2, withSummary(c.get(2), "Membro anonimo", REDACTED_AT));
        return c;
    }

    private static String currentContent(AuditChainLink l) {
        return AuditHashChain.contentHash(l.summary(), l.beforeJson(), l.afterJson());
    }

    @Test
    @DisplayName("contenuto riscritto dall'anonimizzazione con la sua prova REDACT: catena valida, voce contata")
    void redactedWithEvidence() {
        List<AuditChainLink> c = redactedChain();
        RedactionLookup l = lookup(Map.of(c.get(2).id(), evidence(c.get(2), currentContent(c.get(2)))), Instant.EPOCH);
        AuditChainReport r = walk(new AuditChainWalk(SVC, headOf(c), List.of(), l, null), c);
        assertThat(r.ok()).as(r.detail()).isTrue();
        assertThat(r.redacted()).isEqualTo(1);
    }

    @Test
    @DisplayName("voce marcata anonimizzata senza prova REDACT: REDACTION_UNRECORDED")
    void redactedWithoutEvidence() {
        List<AuditChainLink> c = redactedChain();
        assertBroken(walk(new AuditChainWalk(SVC, headOf(c), List.of(), lookup(Map.of(), Instant.EPOCH), null), c),
                3, Reason.REDACTION_UNRECORDED);
        assertBroken(walk(c, headOf(c), List.of()), 3, Reason.REDACTION_UNRECORDED);
    }

    @Test
    @DisplayName("prova REDACT mancante per un'anonimizzazione più vecchia dell'età minima (retention): valida")
    void redactionEvidencePurged() {
        List<AuditChainLink> c = redactedChain();
        RedactionLookup l = lookup(Map.of(), REDACTED_AT.plusSeconds(1));
        AuditChainReport r = walk(new AuditChainWalk(SVC, headOf(c), List.of(), l, null), c);
        assertThat(r.ok()).as(r.detail()).isTrue();
        assertThat(r.redacted()).isEqualTo(1);
    }

    @Test
    @DisplayName("contenuto diverso da quello registrato dalla prova, o prova alterata o di un'altra voce: interruzione")
    void redactionEvidenceMismatch() {
        List<AuditChainLink> c = redactedChain();
        AuditChainLink target = c.get(2);
        RedactionLookup other = lookup(Map.of(target.id(), evidence(target, "f".repeat(64))), Instant.EPOCH);
        assertBroken(walk(new AuditChainWalk(SVC, headOf(c), List.of(), other, null), c), 3, Reason.CONTENT_ALTERED);

        RedactionEvidence good = evidence(target, currentContent(target));
        RedactionEvidence tampered = new RedactionEvidence(withSummary(good.link(), "altro", null), good.targetService(),
                good.targetSeq(), good.contentHash(), good.memberId());
        assertBroken(walk(new AuditChainWalk(SVC, headOf(c), List.of(), lookup(Map.of(target.id(), tampered),
                Instant.EPOCH), null), c), 3, Reason.CONTENT_ALTERED);

        RedactionEvidence elsewhere = new RedactionEvidence(good.link(), good.targetService(), 99L, good.contentHash(),
                good.memberId());
        assertBroken(walk(new AuditChainWalk(SVC, headOf(c), List.of(), lookup(Map.of(target.id(), elsewhere),
                Instant.EPOCH), null), c), 3, Reason.REDACTION_UNRECORDED);
    }

    @Test
    @DisplayName("un campo della voce alterato (attore): ENTRY_ALTERED su quella voce")
    void entryAltered() {
        List<AuditChainLink> c = chain(5);
        AuditChainLink l = c.get(2);
        c.set(2, new AuditChainLink(l.service(), l.seq(), l.prevHash(), l.id(), l.eventId(), l.at(), l.actorRole(),
                "mallory", l.entityType(), l.entityId(), l.action(), l.correlationId(), l.summary(), l.beforeJson(),
                l.afterJson(), l.contentHash(), l.entryHash(), l.redactedAt()));
        assertBroken(walk(c, headOf(c), List.of()), 3, Reason.ENTRY_ALTERED);
    }

    @Test
    @DisplayName("voce riscritta con hash ricalcolati: il collegamento con la successiva si rompe (PREV_HASH_MISMATCH)")
    void resealedEntryBreaksNextLink() {
        List<AuditChainLink> c = chain(5);
        c.set(2, seal(3, c.get(1).entryHash(), "Voce 3", "{\"n\": 3}", "mallory"));
        assertBroken(walk(c, headOf(c), List.of()), 4, Reason.PREV_HASH_MISMATCH);
    }

    @Test
    @DisplayName("voce mancante in mezzo: MISSING_ENTRY sulla posizione mancante")
    void missingInTheMiddle() {
        List<AuditChainLink> c = chain(5);
        AuditChainHead head = headOf(c);
        c.remove(2);
        assertBroken(walk(c, head, List.of()), 3, Reason.MISSING_ENTRY);
    }

    @Test
    @DisplayName("prima voce con seq 1 ma senza genesi: GENESIS_MISMATCH")
    void genesisMismatch() {
        List<AuditChainLink> c = chain(1, "a".repeat(64), 3);
        assertBroken(walk(c, headOf(c), List.of()), 1, Reason.GENESIS_MISMATCH);
    }

    @Test
    @DisplayName("voci iniziali cancellate senza ancora: UNANCHORED_START sulla prima voce rimasta")
    void unanchoredStart() {
        List<AuditChainLink> c = chain(5);
        List<AuditChainLink> kept = new ArrayList<>(c.subList(2, 5));
        assertBroken(walk(kept, headOf(c), List.of()), 3, Reason.UNANCHORED_START);
    }

    @Test
    @DisplayName("retention: la prima voce rimasta si aggancia all'ancora PURGE della precedente")
    void anchoredStartAfterRetention() {
        List<AuditChainLink> c = chain(5);
        List<AuditChainLink> kept = new ArrayList<>(c.subList(2, 5));
        AuditChainReport r = walk(kept, headOf(c), List.of(anchor(c.get(1), AuditAnchor.PURGE)));
        assertThat(r.ok()).as(r.detail()).isTrue();
        assertThat(r.firstSeq()).isEqualTo(3);
        assertThat(r.checked()).isEqualTo(3);
        assertThat(r.anchorsChecked()).isEqualTo(1);
    }

    @Test
    @DisplayName("prima voce rimasta che non si aggancia all'ancora: PREV_HASH_MISMATCH")
    void startAnchorMismatch() {
        List<AuditChainLink> c = chain(5);
        List<AuditChainLink> kept = new ArrayList<>(c.subList(2, 5));
        AuditAnchor wrong = new AuditAnchor(SVC, 2, "e".repeat(64), AuditAnchor.PURGE, Instant.now());
        assertBroken(walk(kept, headOf(c), List.of(wrong)), 3, Reason.PREV_HASH_MISMATCH);
    }

    @Test
    @DisplayName("catena riscritta tutta in modo coerente dopo un'ancora: ANCHOR_MISMATCH sulla voce ancorata")
    void rewrittenAfterAnchor() {
        List<AuditChainLink> original = chain(5);
        AuditAnchor daily = anchor(original.get(3), AuditAnchor.DAILY);
        List<AuditChainLink> forged = new ArrayList<>(original.subList(0, 2));
        String prev = forged.getLast().entryHash();
        for (long seq = 3; seq <= 5; seq++) {
            forged.add(seal(seq, prev, seq == 3 ? "Voce riscritta" : "Voce " + seq, "{\"n\": " + seq + "}", null));
            prev = forged.getLast().entryHash();
        }
        assertBroken(walk(forged, headOf(forged), List.of(daily)), 4, Reason.ANCHOR_MISMATCH);
    }

    @Test
    @DisplayName("ultime voci cancellate, testa invariata: TAIL_MISSING dopo l'ultima presente")
    void tailMissingByHead() {
        List<AuditChainLink> c = chain(5);
        AuditChainHead head = headOf(c);
        assertBroken(walk(c.subList(0, 3), head, List.of()), 4, Reason.TAIL_MISSING);
    }

    @Test
    @DisplayName("ultime voci cancellate e testa riportata indietro: l'ancora oltre l'ultima voce lo rivela")
    void tailMissingByAnchor() {
        List<AuditChainLink> c = chain(5);
        List<AuditChainLink> kept = c.subList(0, 3);
        assertBroken(walk(kept, headOf(kept), List.of(anchor(c.get(4), AuditAnchor.DAILY))), 4, Reason.TAIL_MISSING);
    }

    @Test
    @DisplayName("testa con hash diverso dall'ultima voce: HEAD_MISMATCH")
    void headMismatch() {
        List<AuditChainLink> c = chain(3);
        assertBroken(walk(c, new AuditChainHead(SVC, 3, "b".repeat(64)), List.of()), 3, Reason.HEAD_MISMATCH);
    }

    @Test
    @DisplayName("testa assente con voci presenti: HEAD_MISMATCH")
    void headMissing() {
        List<AuditChainLink> c = chain(3);
        assertBroken(walk(c, null, List.of()), 3, Reason.HEAD_MISMATCH);
    }

    @Test
    @DisplayName("tutte le voci cancellate dalla retention: OK con l'ancora PURGE sulla testa")
    void emptyAfterFullRetention() {
        List<AuditChainLink> c = chain(4);
        AuditChainReport r = walk(List.of(), headOf(c), List.of(anchor(c.getLast(), AuditAnchor.PURGE)));
        assertThat(r.ok()).as(r.detail()).isTrue();
        assertThat(r.checked()).isZero();
        assertThat(r.headSeq()).isEqualTo(4);
        assertThat(r.anchorsChecked()).isEqualTo(1);
    }

    @Test
    @DisplayName("nessuna voce e nessuna ancora per la testa: TAIL_MISSING")
    void emptyWithoutAnchor() {
        List<AuditChainLink> c = chain(4);
        assertBroken(walk(List.of(), headOf(c), List.of()), 4, Reason.TAIL_MISSING);
    }

    @Test
    @DisplayName("dopo la prima interruzione la verifica si ferma: le voci seguenti non sono esaminate")
    void stopsAtFirstBreak() {
        List<AuditChainLink> c = chain(5);
        c.set(1, withSummary(c.get(1), "truccata", null));
        c.set(3, withSummary(c.get(3), "truccata", null));
        AuditChainWalk w = new AuditChainWalk(SVC, headOf(c), List.of());
        assertThat(w.accept(c.get(0))).isTrue();
        assertThat(w.accept(c.get(1))).isFalse();
        assertThat(w.accept(c.get(2))).isFalse();
        AuditChainReport r = w.finish();
        assertBroken(r, 2, Reason.CONTENT_ALTERED);
        assertThat(r.checked()).isEqualTo(2);
    }

    @Test
    @DisplayName("tratto successivo a un'ancora (job giornaliero): verificato agganciandosi all'ancora")
    void segmentAfterAnchor() {
        List<AuditChainLink> c = chain(6);
        AuditAnchor daily = anchor(c.get(2), AuditAnchor.DAILY);
        AuditChainReport r = walk(new AuditChainWalk(SVC, headOf(c), List.of(daily)).startingAfter(daily), c.subList(3, 6));
        assertThat(r.ok()).as(r.detail()).isTrue();
        assertThat(r.checked()).isEqualTo(3);
    }

    // ---------- solo le ancore PURGE giustificano voci mancanti ----------

    @Test
    @DisplayName("voci iniziali mancanti con un'ancora DAILY (non PURGE) sulla precedente: UNANCHORED_START")
    void dailyAnchorDoesNotJustifyAMissingPrefix() {
        List<AuditChainLink> c = chain(5);
        List<AuditChainLink> kept = new ArrayList<>(c.subList(2, 5));
        assertBroken(walk(kept, headOf(c), List.of(anchor(c.get(1), AuditAnchor.DAILY))), 3, Reason.UNANCHORED_START);
    }

    @Test
    @DisplayName("catena vuota con un'ancora DAILY (non PURGE) sulla testa: TAIL_MISSING")
    void dailyAnchorDoesNotJustifyAnEmptyChain() {
        List<AuditChainLink> c = chain(4);
        assertBroken(walk(List.of(), headOf(c), List.of(anchor(c.getLast(), AuditAnchor.DAILY))), 4, Reason.TAIL_MISSING);
    }

    @Test
    @DisplayName("voci e testa sparite, restano le ancore: TAIL_MISSING")
    void onlyAnchorsLeft() {
        List<AuditChainLink> c = chain(4);
        assertBroken(walk(List.of(), null, List.of(anchor(c.getLast(), AuditAnchor.DAILY))), 1, Reason.TAIL_MISSING);
    }

    // ---------- ancora indicata (copiata dai log) ----------

    private static AuditAnchor external(long seq, String hash) {
        return new AuditAnchor(SVC, seq, hash, AuditAnchor.EXTERNAL, null);
    }

    private static AuditChainReport walkExpecting(List<AuditChainLink> links, AuditChainHead head,
                                                  List<AuditAnchor> anchors, AuditAnchor expected) {
        return walk(new AuditChainWalk(SVC, head, anchors, RedactionLookup.NONE, expected), links);
    }

    @Test
    @DisplayName("ancora indicata presente con lo stesso hash: MATCH, catena OK")
    void expectedAnchorMatches() {
        List<AuditChainLink> c = chain(5);
        AuditChainReport r = walkExpecting(c, headOf(c), List.of(), external(3, c.get(2).entryHash()));
        assertThat(r.ok()).as(r.detail()).isTrue();
        assertThat(r.expectedAnchor().result()).isEqualTo(ExpectedAnchor.Result.MATCH);
        assertThat(r.anchorsChecked()).as("l'ancora indicata non conta tra quelle registrate").isZero();
    }

    @Test
    @DisplayName("catena riscritta in modo coerente, senza ancore nel database: la rivela solo l'ancora dei log")
    void expectedAnchorCatchesAConsistentRewrite() {
        List<AuditChainLink> original = chain(5);
        List<AuditChainLink> forged = new ArrayList<>(original.subList(0, 2));
        String prev = forged.getLast().entryHash();
        for (long seq = 3; seq <= 5; seq++) {
            forged.add(seal(seq, prev, seq == 3 ? "Voce riscritta" : "Voce " + seq, "{\"n\": " + seq + "}", null));
            prev = forged.getLast().entryHash();
        }
        assertThat(walk(forged, headOf(forged), List.of()).ok()).isTrue();
        AuditChainReport r = walkExpecting(forged, headOf(forged), List.of(), external(4, original.get(3).entryHash()));
        assertBroken(r, 4, Reason.ANCHOR_MISMATCH);
        assertThat(r.expectedAnchor().result()).isEqualTo(ExpectedAnchor.Result.MISMATCH);
    }

    @Test
    @DisplayName("ancora indicata oltre l'ultima voce (coda cancellata e testa riportata indietro): MISSING")
    void expectedAnchorBeyondTheChain() {
        List<AuditChainLink> c = chain(5);
        List<AuditChainLink> kept = c.subList(0, 3);
        AuditChainReport r = walkExpecting(kept, headOf(kept), List.of(), external(5, c.get(4).entryHash()));
        assertBroken(r, 4, Reason.TAIL_MISSING);
        assertThat(r.expectedAnchor().result()).isEqualTo(ExpectedAnchor.Result.MISSING);
    }

    @Test
    @DisplayName("ancora indicata su una catena sparita del tutto: MISSING")
    void expectedAnchorOnAVanishedChain() {
        List<AuditChainLink> c = chain(5);
        AuditChainReport r = walkExpecting(List.of(), null, List.of(), external(5, c.get(4).entryHash()));
        assertBroken(r, 1, Reason.TAIL_MISSING);
        assertThat(r.expectedAnchor().result()).isEqualTo(ExpectedAnchor.Result.MISSING);
    }

    @Test
    @DisplayName("ancora indicata su una voce cancellata dalla retention: PURGED con l'istante della cancellazione")
    void expectedAnchorPurged() {
        List<AuditChainLink> c = chain(6);
        Instant purgedAt = Instant.parse("2026-09-20T00:20:00Z");
        AuditAnchor purge = new AuditAnchor(SVC, 3, c.get(2).entryHash(), AuditAnchor.PURGE, purgedAt);
        AuditChainReport r = walkExpecting(new ArrayList<>(c.subList(3, 6)), headOf(c), List.of(purge),
                external(2, c.get(1).entryHash()));
        assertThat(r.ok()).as(r.detail()).isTrue();
        assertThat(r.expectedAnchor().result()).isEqualTo(ExpectedAnchor.Result.PURGED);
        assertThat(r.expectedAnchor().purgedAt()).isEqualTo(purgedAt);
    }

    @Test
    @DisplayName("ancora indicata sull'ultima voce cancellata: PURGED se coincide con il prev_hash della prima rimasta")
    void expectedAnchorAtTheRetentionBoundary() {
        List<AuditChainLink> c = chain(6);
        AuditAnchor purge = anchor(c.get(2), AuditAnchor.PURGE);
        AuditChainReport ok = walkExpecting(new ArrayList<>(c.subList(3, 6)), headOf(c), List.of(purge),
                external(3, c.get(2).entryHash()));
        assertThat(ok.ok()).as(ok.detail()).isTrue();
        assertThat(ok.expectedAnchor().result()).as("la voce 3 non c'è più: mai MATCH").isEqualTo(ExpectedAnchor.Result.PURGED);
        assertThat(ok.expectedAnchor().purgedAt()).isEqualTo(purge.anchoredAt());
        AuditChainReport ko = walkExpecting(new ArrayList<>(c.subList(3, 6)), headOf(c), List.of(purge),
                external(3, "9".repeat(64)));
        assertBroken(ko, 3, Reason.ANCHOR_MISMATCH);
    }

    @Test
    @DisplayName("catena interrotta prima dell'ancora indicata: UNCHECKED")
    void expectedAnchorUnchecked() {
        List<AuditChainLink> c = chain(5);
        c.set(1, withSummary(c.get(1), "truccata", null));
        AuditChainReport r = walkExpecting(c, headOf(c), List.of(), external(4, c.get(3).entryHash()));
        assertBroken(r, 2, Reason.CONTENT_ALTERED);
        assertThat(r.expectedAnchor().result()).isEqualTo(ExpectedAnchor.Result.UNCHECKED);
    }

    @Test
    @DisplayName("catena svuotata: l'ancora indicata sulla testa è PURGED se coincide, MISMATCH se no")
    void expectedAnchorOnAnEmptiedChain() {
        List<AuditChainLink> c = chain(4);
        AuditAnchor purge = anchor(c.getLast(), AuditAnchor.PURGE);
        AuditChainReport same = walkExpecting(List.of(), headOf(c), List.of(purge), external(4, c.getLast().entryHash()));
        assertThat(same.ok()).as(same.detail()).isTrue();
        assertThat(same.expectedAnchor().result()).isEqualTo(ExpectedAnchor.Result.PURGED);
        AuditChainReport other = walkExpecting(List.of(), headOf(c), List.of(purge), external(4, "7".repeat(64)));
        assertBroken(other, 4, Reason.ANCHOR_MISMATCH);
        assertThat(other.expectedAnchor().result()).isEqualTo(ExpectedAnchor.Result.MISMATCH);
    }

    // ---------- cancellazioni troppo recenti ----------

    private static final Duration MIN = Duration.ofDays(180);
    private static final Instant PURGED_ON = Instant.parse("2026-09-28T00:20:00Z");

    private static AuditAnchor purge(AuditChainLink last, Instant newestDeleted) {
        return new AuditAnchor(SVC, last.seq(), last.entryHash(), AuditAnchor.PURGE, PURGED_ON, newestDeleted);
    }

    @Test
    @DisplayName("ancora PURGE di voci più vecchie dell'età minima: retention regolare")
    void purgeOfOldEntriesIsFine() {
        List<AuditChainLink> c = chain(5);
        AuditAnchor p = purge(c.get(1), PURGED_ON.minus(MIN).minusSeconds(1));
        AuditChainReport r = walk(new AuditChainWalk(SVC, headOf(c), List.of(p), RedactionLookup.NONE, null, MIN),
                new ArrayList<>(c.subList(2, 5)));
        assertThat(r.ok()).as(r.detail()).isTrue();
    }

    @Test
    @DisplayName("ancora PURGE di voci più giovani dell'età minima: PURGE_TOO_RECENT sulla voce dell'ancora")
    void purgeOfRecentEntriesIsFlagged() {
        List<AuditChainLink> c = chain(5);
        AuditAnchor p = purge(c.get(1), PURGED_ON.minus(Duration.ofDays(3)));
        assertBroken(walk(new AuditChainWalk(SVC, headOf(c), List.of(p), RedactionLookup.NONE, null, MIN),
                new ArrayList<>(c.subList(2, 5))), 2, Reason.PURGE_TOO_RECENT);
        AuditAnchor all = purge(c.getLast(), PURGED_ON.minus(Duration.ofDays(3)));
        assertBroken(walk(new AuditChainWalk(SVC, headOf(c), List.of(all), RedactionLookup.NONE, null, MIN), List.of()),
                5, Reason.PURGE_TOO_RECENT);
    }
}
