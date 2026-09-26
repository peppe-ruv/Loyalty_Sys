package io.loyaltyhub.campaign.messaging;

import io.loyaltyhub.campaign.infra.MemberSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.function.LongConsumer;
import java.util.function.Predicate;

/**
 * Azione per un membro assente dallo snapshot (docs/servizi/campaign-service.md §5): «ritenta 3 volte (il fatto
 * {@code member.registered} può essere in arrivo), poi {@code NO_MEMBER}». Il fatto arriva su {@code lh.facts.v1}
 * e l'azione (es. quella del ponte interno {@code member.registered}, che attiva il bonus di benvenuto) su
 * {@code lh.actions.v1}: due consumatori distinti, nessun ordine garantito tra i topic.
 * <p>
 * L'attesa avviene prima della transazione del consumer idempotente, rileggendo lo snapshot dopo ogni ritardo;
 * scaduti i tentativi la valutazione procede e registra {@code NO_MEMBER} come prima, senza DLQ. Scatta solo per
 * uno snapshot assente: un membro noto ma non {@code ACTIVE} non attende. SPEC-GAP: Q-169 (intervalli).
 */
@Component
public class MemberSnapshotAwait {

    private static final Logger log = LoggerFactory.getLogger(MemberSnapshotAwait.class);

    private final Predicate<String> known;
    private final long[] delaysMs;
    private final LongConsumer sleeper;

    @Autowired
    public MemberSnapshotAwait(MemberSnapshotRepository snapshots,
                               @Value("${loyaltyhub.engine.member-wait-ms:500,1000,2000}") long[] delaysMs) {
        this(snapshots::exists, delaysMs, MemberSnapshotAwait::sleep);
    }

    MemberSnapshotAwait(Predicate<String> known, long[] delaysMs, LongConsumer sleeper) {
        this.known = known;
        this.delaysMs = delaysMs != null ? delaysMs : new long[0];
        this.sleeper = sleeper;
    }

    /** @return {@code true} se lo snapshot del membro c'è (subito o dopo un ritentativo). */
    public boolean await(String memberId) {
        if (memberId == null || known.test(memberId)) {
            return true;
        }
        for (int attempt = 0; attempt < delaysMs.length; attempt++) {
            sleeper.accept(delaysMs[attempt]);
            if (known.test(memberId)) {
                log.info("Snapshot di {} arrivato al ritentativo {}/{}", memberId, attempt + 1, delaysMs.length);
                return true;
            }
        }
        log.warn("Snapshot di {} assente dopo {} ritentativi: la valutazione registra NO_MEMBER",
                memberId, delaysMs.length);
        return false;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
