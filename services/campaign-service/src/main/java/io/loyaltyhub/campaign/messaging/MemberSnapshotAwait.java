package io.loyaltyhub.campaign.messaging;

import io.loyaltyhub.campaign.infra.MemberSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
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
 * Regole dell'attesa, per record ({@code topic-partizione@offset}):
 * <ul>
 *   <li>le riletture seguono i ritardi {@code loyaltyhub.engine.member-wait-ms} (0,5 s, 1 s, 2 s);</li>
 *   <li>si rinuncia ({@code NO_MEMBER}, senza DLQ) solo quando i tentativi sono esauriti <em>e</em> dalla prima consegna è
 *       passata almeno la somma dei ritardi: un ribilanciamento che riconsegna subito il record non consuma l'attesa;</li>
 *   <li>dopo la rinuncia il record resta «esaurito» finché il listener non conferma la valutazione ({@link #done}): se la
 *       valutazione fallisce e il record torna, non riparte un nuovo ciclo di attese.</li>
 * </ul>
 * Scatta solo per uno snapshot assente: un membro noto ma non {@code ACTIVE} non attende. Tutto avviene prima della
 * transazione del consumer idempotente. SPEC-GAP: Q-169 (intervalli).
 */
@Component
public class MemberSnapshotAwait {

    private static final Logger log = LoggerFactory.getLogger(MemberSnapshotAwait.class);

    /** Oltre questa età un conteggio è di un record passato ad altra istanza o finito in DLQ: si scarta. */
    private static final long STALE_NANOS = Duration.ofMinutes(10).toNanos();
    private static final int PRUNE_ABOVE = 256;

    private final Predicate<String> known;
    private final long[] delaysMs;
    private final long totalNanos;
    private final LongSupplier clock;
    private final Map<String, Attempts> pending = new ConcurrentHashMap<>();

    /** Attese già fatte per un record, istante della prima consegna, rinuncia già decisa. */
    private record Attempts(int waits, long firstSeenNanos, boolean exhausted) {
    }

    @Autowired
    public MemberSnapshotAwait(MemberSnapshotRepository snapshots,
                               @Value("${loyaltyhub.engine.member-wait-ms:500,1000,2000}") long[] delaysMs) {
        this(snapshots::exists, delaysMs, System::nanoTime);
    }

    MemberSnapshotAwait(Predicate<String> known, long[] delaysMs, LongSupplier clock) {
        this.known = known;
        this.delaysMs = delaysMs != null ? delaysMs : new long[0];
        this.totalNanos = Duration.ofMillis(Arrays.stream(this.delaysMs).sum()).toNanos();
        this.clock = clock;
    }

    /**
     * Da chiamare a ogni consegna del record, prima di valutarlo.
     *
     * @param recordKey identità stabile del record tra le riconsegne ({@code topic-partizione@offset})
     * @return {@code null} se la valutazione può procedere (snapshot presente, nessun membro o attesa esaurita);
     *         altrimenti il ritardo dopo cui riconsegnare il record ({@code Acknowledgment#nack}) e rileggere lo snapshot
     */
    public Duration retryDelay(String recordKey, String memberId) {
        if (memberId == null) {
            return null;
        }
        Attempts previous = pending.get(recordKey);
        if (previous != null && previous.exhausted()) {
            return null; // rinuncia già decisa: la valutazione (NO_MEMBER) è in corso o viene ritentata
        }
        if (known.test(memberId)) {
            if (previous != null) {
                pending.remove(recordKey);
                log.info("Snapshot di {} arrivato al ritentativo {}/{}", memberId, previous.waits(), delaysMs.length);
            }
            return null;
        }
        long now = clock.getAsLong();
        long firstSeen = previous == null ? now : previous.firstSeenNanos();
        int waits = previous == null ? 0 : previous.waits();
        prune(now);
        if (waits < delaysMs.length) {
            pending.put(recordKey, new Attempts(waits + 1, firstSeen, false));
            return Duration.ofMillis(delaysMs[waits]);
        }
        long remaining = totalNanos - (now - firstSeen);
        if (remaining > 0) {
            // Tentativi consumati da riconsegne anticipate (ribilanciamento): si attende il tempo che manca.
            pending.put(recordKey, new Attempts(waits + 1, firstSeen, false));
            return Duration.ofNanos(remaining);
        }
        pending.put(recordKey, new Attempts(waits, firstSeen, true));
        log.warn("Snapshot di {} assente dopo {} ritentativi: la valutazione registra NO_MEMBER",
                memberId, delaysMs.length);
        return null;
    }

    /** Valutazione del record confermata (ack): dimentica il suo conteggio. */
    public void done(String recordKey) {
        pending.remove(recordKey);
    }

    /** Record con un'attesa in corso o una rinuncia non ancora confermata (per test e diagnostica). */
    public int pendingCount() {
        return pending.size();
    }

    private void prune(long now) {
        if (pending.size() > PRUNE_ABOVE) {
            pending.values().removeIf(a -> now - a.firstSeenNanos() > STALE_NANOS);
        }
    }
}
