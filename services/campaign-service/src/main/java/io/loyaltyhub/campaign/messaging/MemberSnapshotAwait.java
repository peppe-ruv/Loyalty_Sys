package io.loyaltyhub.campaign.messaging;

import io.loyaltyhub.campaign.infra.MemberSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Azione per un membro assente dallo snapshot (docs/servizi/campaign-service.md §5): «ritenta 3 volte (il fatto
 * {@code member.registered} può essere in arrivo), poi {@code NO_MEMBER}». Il fatto arriva su {@code lh.facts.v1}
 * e l'azione (es. quella del ponte interno {@code member.registered}, che attiva il bonus di benvenuto) su
 * {@code lh.actions.v1}: due consumatori distinti, nessun ordine garantito tra i topic.
 * <p>
 * <strong>L'attesa non blocca il consumer</strong> (Q-489). I due listener sono nello stesso gruppo {@code lh-campaign}:
 * se il gruppo sta ribilanciando (un consumer che entra all'avvio, un'istanza che si aggiunge), il consumer dei fatti
 * riceve le sue partizioni solo quando tutti i membri, compreso quello delle azioni, sono tornati a {@code poll()}.
 * Un {@code Thread.sleep} nel listener delle azioni teneva fermo proprio quel ribilanciamento: il fatto atteso non poteva
 * arrivare prima della fine dei tentativi e la valutazione registrava sempre {@code NO_MEMBER} (bonus di benvenuto
 * perso, TB-PLT-FRP-003). Ora il listener riceve un ritardo e fa {@code nack}: il container mette in pausa le partizioni
 * e continua a interrogare il broker (il ribilanciamento procede), poi riconsegna lo stesso record.
 * <p>
 * I tentativi si contano per record ({@code topic-partizione@offset}) e si rileggono dopo 0,5 s, 1 s e 2 s
 * ({@code loyaltyhub.engine.member-wait-ms}); scaduti, la valutazione procede e registra {@code NO_MEMBER} come prima,
 * senza DLQ. Scatta solo per uno snapshot assente: un membro noto ma non {@code ACTIVE} non attende. Tutto avviene prima
 * della transazione del consumer idempotente. SPEC-GAP: Q-169 (intervalli).
 */
@Component
public class MemberSnapshotAwait {

    private static final Logger log = LoggerFactory.getLogger(MemberSnapshotAwait.class);

    /** Oltre questa età un conteggio è di un record passato ad altra istanza dopo un ribilanciamento: si scarta. */
    private static final long STALE_NANOS = Duration.ofMinutes(10).toNanos();
    private static final int PRUNE_ABOVE = 256;

    private final Predicate<String> known;
    private final long[] delaysMs;
    private final LongSupplier clock;
    private final Map<String, Attempts> pending = new ConcurrentHashMap<>();

    private record Attempts(int waits, long firstSeenNanos) {
    }

    @Autowired
    public MemberSnapshotAwait(MemberSnapshotRepository snapshots,
                               @Value("${loyaltyhub.engine.member-wait-ms:500,1000,2000}") long[] delaysMs) {
        this(snapshots::exists, delaysMs, System::nanoTime);
    }

    MemberSnapshotAwait(Predicate<String> known, long[] delaysMs, LongSupplier clock) {
        this.known = known;
        this.delaysMs = delaysMs != null ? delaysMs : new long[0];
        this.clock = clock;
    }

    /**
     * Da chiamare a ogni consegna del record, prima di valutarlo.
     *
     * @param recordKey identità stabile del record tra le riconsegne ({@code topic-partizione@offset})
     * @return {@code null} se la valutazione può procedere (snapshot presente, nessun membro o tentativi esauriti);
     *         altrimenti il ritardo dopo cui riconsegnare il record ({@code Acknowledgment#nack}) e rileggere lo snapshot
     */
    public Duration retryDelay(String recordKey, String memberId) {
        if (memberId == null) {
            return null;
        }
        if (known.test(memberId)) {
            Attempts done = pending.remove(recordKey);
            if (done != null) {
                log.info("Snapshot di {} arrivato al ritentativo {}/{}", memberId, done.waits(), delaysMs.length);
            }
            return null;
        }
        Attempts previous = pending.get(recordKey);
        int waits = previous == null ? 0 : previous.waits();
        if (waits >= delaysMs.length) {
            pending.remove(recordKey);
            log.warn("Snapshot di {} assente dopo {} ritentativi: la valutazione registra NO_MEMBER",
                    memberId, delaysMs.length);
            return null;
        }
        long now = clock.getAsLong();
        pending.put(recordKey, new Attempts(waits + 1, previous == null ? now : previous.firstSeenNanos()));
        prune(now);
        return Duration.ofMillis(delaysMs[waits]);
    }

    /** Record in attesa (per i test). */
    int pendingCount() {
        return pending.size();
    }

    private void prune(long now) {
        if (pending.size() > PRUNE_ABOVE) {
            pending.values().removeIf(a -> now - a.firstSeenNanos() > STALE_NANOS);
        }
    }
}
