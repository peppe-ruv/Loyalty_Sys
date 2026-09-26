package io.loyaltyhub.insight.live;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consegna del bus SSE (docs/servizi/insight-service.md §3, §5, §7) senza rete né Spring: emitter finti che
 * registrano gli id inviati, o che restano bloccati nell'invio come un client che non legge. Le interleaving sono
 * forzate con latch (nessuna pausa come oracolo): i casi sono deterministici.
 */
class LiveEventHubTest {

    private final LiveEventHub hub = new LiveEventHub();
    private final List<RecordingEmitter> emitters = new CopyOnWriteArrayList<>();

    @AfterEach
    void shutdown() {
        emitters.forEach(RecordingEmitter::release);
        hub.shutdown();
    }

    private static final LiveEventHub.Filter ALL = new LiveEventHub.Filter(Set.of(), Set.of(), null, null);

    private static LiveEvent event(String id) {
        return new LiveEvent(id, "lh.actions.v1", "ACTION", "app.login.daily", "MBR-000002", "COR-HUB",
                Instant.parse("2026-09-15T10:00:00Z"), "Accesso all'app");
    }

    private RecordingEmitter emitter(boolean blocking) {
        RecordingEmitter e = new RecordingEmitter(blocking);
        emitters.add(e);
        return e;
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Attesa scaduta: " + what);
            }
            Thread.sleep(5);
        }
    }

    @Test
    @DisplayName("[TB-INS-SRP-013 regressione] evento pubblicato mentre un altro client è bloccato e un nuovo client "
            + "si iscrive con Last-Event-ID: arriva una volta sola (rinvio e vivo si passano il testimone)")
    void replayLiveHandoffHasNoDuplicates() throws Exception {
        // A non legge: il suo invio resta bloccato sul primo evento. Con un solo thread d'invio condiviso la consegna
        // di E1 restava in coda dietro A e, quando ripartiva, trovava già iscritto B: E1 arrivava a B dal vivo e dal
        // rinvio (il buffer era copiato dopo l'iscrizione). Ora ogni client ha coda e thread propri e l'iscrizione
        // copia il buffer sotto lo stesso lock della pubblicazione.
        RecordingEmitter a = emitter(true);
        hub.subscribe(ALL, null, a);
        hub.publish(event("E0"));
        assertThat(a.entered.await(10, TimeUnit.SECONDS)).as("A bloccato nell'invio di E0").isTrue();

        hub.publish(event("E1"));
        RecordingEmitter b = emitter(false);
        hub.subscribe(ALL, "E0", b);
        hub.publish(event("E2"));
        a.release();
        hub.publish(event("E3"));
        await("B riceve E3", () -> b.ids.contains("E3"));

        assertThat(b.ids).as("E1 dal rinvio, E2 ed E3 dal vivo, una volta sola").containsExactly("E1", "E2", "E3");
        await("A riceve E3", () -> a.ids.contains("E3"));
        assertThat(a.ids).containsExactly("E0", "E1", "E2", "E3");
    }

    @Test
    @DisplayName("[TB-INS-SRP-013 regressione] riconnessione con 20 eventi: pubblicazioni concorrenti all'iscrizione, "
            + "nessun evento perso né doppio")
    void reconnectWhilePublishing() throws Exception {
        for (int round = 0; round < 50; round++) {
            String prefix = "R" + round + "-";
            for (int i = 0; i < 10; i++) {
                hub.publish(event(prefix + i));
            }
            CountDownLatch go = new CountDownLatch(1);
            Thread publisher = Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int i = 10; i < 20; i++) {
                    hub.publish(event(prefix + i));
                }
            });
            RecordingEmitter c = emitter(false);
            go.countDown();
            hub.subscribe(ALL, prefix + 9, c);
            publisher.join();
            await("ultimo evento del giro " + round, () -> c.ids.contains(prefix + 19));
            List<String> expected = new java.util.ArrayList<>();
            for (int i = 10; i < 20; i++) {
                expected.add(prefix + i);
            }
            assertThat(c.ids).as("giro " + round).containsExactlyElementsOf(expected);
            c.complete();
        }
    }

    @Test
    @DisplayName("[TB-INS-SSE-009 unità] client che non legge: oltre 500 messaggi in coda è disconnesso, l'altro riceve tutto")
    void slowClientIsDisconnected() throws Exception {
        RecordingEmitter slow = emitter(true);
        RecordingEmitter healthy = emitter(false);
        hub.subscribe(ALL, null, slow);
        hub.subscribe(ALL, null, healthy);
        // Prima si riempie la coda del lento e si attende che il sano abbia svuotato la sua: pubblicando tutto di fila,
        // su un runner lento anche il sano superava i 500 in coda e veniva disconnesso (0 iscritti invece di 1).
        int full = LiveEventHub.CLIENT_QUEUE;
        int n = full + 10;
        for (int i = 0; i < full; i++) {
            hub.publish(event("S" + i));
        }
        await("il client sano svuota la coda", () -> healthy.ids.size() == full);
        for (int i = full; i < n; i++) {
            hub.publish(event("S" + i));
        }
        assertThat(hub.subscriberCount()).as("il client lento è tolto dagli iscritti").isEqualTo(1);
        await("il client sano riceve tutto", () -> healthy.ids.size() == n);
        assertThat(healthy.ids.get(n - 1)).isEqualTo("S" + (n - 1));
    }

    @Test
    @DisplayName("[TB-INS-SRP-004 unità] Last-Event-ID fuori dal buffer: nessun rinvio (Q-322), poi dal vivo")
    void unknownLastEventId() throws Exception {
        hub.publish(event("U0"));
        hub.publish(event("U1"));
        RecordingEmitter c = emitter(false);
        hub.subscribe(ALL, "MAI-VISTO", c);
        hub.publish(event("U2"));
        await("U2", () -> c.ids.contains("U2"));
        assertThat(c.ids).containsExactly("U2");
    }

    @Test
    @DisplayName("[TB-INS-SRP-009 unità] Last-Event-ID appena uscito dal buffer (201 persi): rinviati gli ultimi 200")
    void evictedLastEventId() throws Exception {
        hub.publish(event("V-marker"));
        for (int i = 0; i < LiveEventHub.REPLAY_BUFFER + 1; i++) {
            hub.publish(event("V" + i));
        }
        RecordingEmitter c = emitter(false);
        hub.subscribe(ALL, "V-marker", c);
        await("ultimo", () -> c.ids.contains("V" + LiveEventHub.REPLAY_BUFFER));
        assertThat(c.ids).hasSize(LiveEventHub.REPLAY_BUFFER).first().isEqualTo("V1");
    }

    /** Emitter finto: registra l'id di ogni evento inviato; {@code blocking} = resta nell'invio fino a {@link #release}. */
    static final class RecordingEmitter extends SseEmitter {
        final List<String> ids = new CopyOnWriteArrayList<>();
        final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released;

        RecordingEmitter(boolean blocking) {
            super(0L);
            this.released = new CountDownLatch(blocking ? 1 : 0);
        }

        void release() {
            released.countDown();
        }

        @Override
        public void send(SseEventBuilder builder) {
            String id = idOf(builder.build());
            if (id == null) {
                return; // heartbeat
            }
            entered.countDown();
            try {
                released.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrotto", e);
            }
            ids.add(id);
        }

        private static String idOf(Set<ResponseBodyEmitter.DataWithMediaType> parts) {
            for (ResponseBodyEmitter.DataWithMediaType p : parts) {
                if (p.getData() instanceof String s) {
                    for (String line : s.split("\n")) {
                        if (line.startsWith("id:")) {
                            return line.substring(3).trim();
                        }
                    }
                }
            }
            return null;
        }
    }
}
