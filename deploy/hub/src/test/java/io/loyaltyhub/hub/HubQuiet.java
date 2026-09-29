package io.loyaltyhub.hub;

import io.loyaltyhub.hub.bus.HubInProcessBus;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Quiete del deployable consolidato col bus in-process (profilo {@code inproc}, ADR-024), al posto delle attese fisse
 * negli IT dell'hub (docs/06 §9): come {@code TestbookE2eSupportIT.quiet()}, senza finestra di tempo.
 * <p>
 * Il bus consegna su un solo thread FIFO: quando un record-barriera su un topic privato del test arriva, tutto ciò che
 * era in coda prima è consegnato. Il sistema è quieto quando, per <em>due intervalli consecutivi</em> tra barriere,
 * l'outbox non ha righe da pubblicare e non cambiano i conteggi di outbox, {@code processed_event},
 * {@code insight.event_store} e {@code insight.dlq_entry}. Un intervallo solo non basta: un record consegnato in
 * quell'intervallo che fallisce non cambia nessun conteggio (la transazione è annullata) e il bus accoda il suo record
 * DLQ <em>dopo</em> la barriera che chiude l'intervallo; nell'intervallo successivo insight scrive {@code dlq_entry}.
 * Il primo conteggio si prende dopo una barriera, mai prima: un fallimento ancora in coda non passa per quiete.
 */
public final class HubQuiet {

    /** Topic privato del test: nessun servizio lo ascolta. */
    private static final String BARRIER_TOPIC = "lh.test.quiet-barrier";
    private static final long TIMEOUT_MS = 90_000;
    private static final Map<HubInProcessBus, Map<String, CountDownLatch>> LATCHES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private HubQuiet() {
    }

    /** Ritorna quando l'hub è quieto; altrimenti fallisce allo scadere con gli ultimi conteggi osservati. */
    public static void await(HubInProcessBus bus, JdbcClient jdbc) {
        await(bus, jdbc, "quiete");
    }

    /** Come {@link #await(HubInProcessBus, JdbcClient)}; {@code phase} compare nel messaggio d'errore. */
    public static void await(HubInProcessBus bus, JdbcClient jdbc, String phase) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        barrier(bus);
        List<Long> previous = counters(jdbc);
        int stable = 0;
        while (System.currentTimeMillis() < deadline) {
            barrier(bus);
            List<Long> current = counters(jdbc);
            stable = current.get(1) == 0 && current.equals(previous) ? stable + 1 : 0;
            if (stable == 2) {
                return;
            }
            previous = current;
        }
        throw new AssertionError("hub non quieto entro " + TIMEOUT_MS / 1000 + " s (" + phase + "; outbox, da pubblicare, "
                + "processed_event, event_store, dlq_entry): " + previous);
    }

    /** Barriera FIFO sul thread di consegna del bus: ritorna quando è consegnato tutto ciò che la precede. */
    public static void barrier(HubInProcessBus bus) {
        Map<String, CountDownLatch> latches = LATCHES.computeIfAbsent(bus, b -> {
            Map<String, CountDownLatch> m = new ConcurrentHashMap<>();
            b.subscribe(BARRIER_TOPIC, "lh-test-quiet", r -> {
                CountDownLatch latch = m.remove(r.key());
                if (latch != null) {
                    latch.countDown();
                }
            });
            return m;
        });
        String id = UUID.randomUUID().toString();
        CountDownLatch latch = new CountDownLatch(1);
        latches.put(id, latch);
        bus.publish(new ProducerRecord<>(BARRIER_TOPIC, id, "{}"));
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new AssertionError("barriera del bus in-process non consegnata entro 60 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("attesa interrotta", e);
        }
    }

    private static List<Long> counters(JdbcClient jdbc) {
        return List.of(
                count(jdbc, "SELECT count(*) FROM outbox"),
                count(jdbc, "SELECT count(*) FROM outbox WHERE published_at IS NULL"),
                count(jdbc, "SELECT count(*) FROM processed_event"),
                count(jdbc, "SELECT count(*) FROM insight.event_store"),
                count(jdbc, "SELECT count(*) FROM insight.dlq_entry"));
    }

    private static long count(JdbcClient jdbc, String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
