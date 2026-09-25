package io.loyaltyhub.insight.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PreDestroy;

/**
 * Bus SSE in memoria (docs/servizi/insight-service.md §3, §5, ADR-020): l'ingest pubblica qui ogni evento nuovo e
 * gli emitter connessi lo ricevono, filtrati per topic/tipo/membro/correlazione. Heartbeat ogni 15 s;
 * {@code Last-Event-ID} → rinvio degli ultimi ≤ 200 (ring buffer).
 * <p>
 * Ogni client ha una <strong>coda limitata a 500</strong> messaggi e un proprio thread (virtuale) d'invio: un client
 * lento blocca solo il suo invio; quando la sua coda è piena è <strong>disconnesso</strong> e gli altri continuano
 * (insight §5). Pubblicazione e iscrizione passano dallo stesso lock: il rinvio dal buffer e la consegna dal vivo si
 * passano il testimone in modo atomico, così un evento arriva una volta sola e nessuno cade nel mezzo (insight §7).
 */
@Component
public class LiveEventHub {

    private static final Logger log = LoggerFactory.getLogger(LiveEventHub.class);
    static final int REPLAY_BUFFER = 200;
    /** Messaggi in attesa per client oltre i quali il client è considerato lento e disconnesso (insight §5). */
    static final int CLIENT_QUEUE = 500;
    private static final long EMITTER_TIMEOUT_MS = 3_600_000L; // 1 h; il client riconnette (EventSource)
    private static final Object HEARTBEAT = new Object();
    private static final Object CLOSE = new Object();

    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final Deque<LiveEvent> recent = new ArrayDeque<>();
    /**
     * Id usciti di recente dal buffer (al più {@link #EVICTED_MEMORY}): un {@code Last-Event-ID} fra questi è di un
     * client rimasto indietro di oltre 200 eventi e riceve tutto il buffer (gli ultimi 200 persi, insight §3).
     */
    private final Set<String> evicted = new LinkedHashSet<>();
    static final int EVICTED_MEMORY = 5_000;
    /** Protegge {@link #recent} e l'ordine tra pubblicazione e iscrizione (handoff rinvio → vivo). */
    private final Object lock = new Object();
    private final ScheduledExecutorService heartbeats =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "lh-insight-sse-hb");
                t.setDaemon(true);
                return t;
            });

    public LiveEventHub() {
        heartbeats.scheduleAtFixedRate(this::heartbeat, 15, 15, TimeUnit.SECONDS);
    }

    /** Filtro di una sottoscrizione: valori nulli/vuoti = "tutto". {@code types} confronta lo short type. */
    public record Filter(Set<String> topics, Set<String> types, String memberId, String correlationId) {
        boolean matches(LiveEvent e) {
            if (topics != null && !topics.isEmpty() && !topics.contains(e.topic())) {
                return false;
            }
            if (types != null && !types.isEmpty() && !types.contains(e.shortType())) {
                return false;
            }
            if (memberId != null && !memberId.isEmpty() && !memberId.equals(e.memberId())) {
                return false;
            }
            return correlationId == null || correlationId.isEmpty() || correlationId.equals(e.correlationId());
        }
    }

    /** Apre uno stream SSE; se {@code lastEventId} è nel buffer rinvia gli eventi successivi. */
    public SseEmitter subscribe(Filter filter, String lastEventId) {
        return subscribe(filter, lastEventId, new SseEmitter(EMITTER_TIMEOUT_MS));
    }

    /** Come {@link #subscribe(Filter, String)} con un emitter dato (test). */
    SseEmitter subscribe(Filter filter, String lastEventId, SseEmitter emitter) {
        Subscriber sub = new Subscriber(emitter, filter);
        emitter.onCompletion(() -> close(sub));
        emitter.onTimeout(() -> close(sub));
        emitter.onError(e -> close(sub));
        synchronized (lock) {
            // Sotto lo stesso lock di publish: ciò che è nel buffer arriva dal rinvio, ciò che segue dal vivo.
            for (LiveEvent e : replaySince(lastEventId)) {
                if (filter.matches(e)) {
                    sub.queue.offer(e); // al più REPLAY_BUFFER < CLIENT_QUEUE: c'è sempre posto
                }
            }
            subscribers.add(sub);
        }
        sub.start();
        return emitter;
    }

    /** Pubblica un evento nuovo: lo accoda ai soli client che lo vogliono (mai bloccante). */
    public void publish(LiveEvent event) {
        List<Subscriber> slow = new ArrayList<>();
        synchronized (lock) {
            recent.addLast(event);
            while (recent.size() > REPLAY_BUFFER) {
                evicted.add(recent.removeFirst().eventId());
                if (evicted.size() > EVICTED_MEMORY) {
                    Iterator<String> oldest = evicted.iterator();
                    oldest.next();
                    oldest.remove();
                }
            }
            for (Subscriber sub : subscribers) {
                if (sub.filter.matches(event) && !sub.queue.offer(event)) {
                    slow.add(sub);
                }
            }
        }
        slow.forEach(this::disconnectSlow);
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    private void heartbeat() {
        for (Subscriber sub : subscribers) {
            if (!sub.queue.offer(HEARTBEAT)) {
                disconnectSlow(sub);
            }
        }
    }

    private void disconnectSlow(Subscriber sub) {
        if (subscribers.contains(sub)) {
            log.info("Client SSE lento (oltre {} messaggi in coda): disconnesso", CLIENT_QUEUE);
        }
        close(sub);
    }

    /** Toglie il client dagli iscritti e ferma il suo invio; la chiusura dell'emitter la fa il suo thread. */
    private void close(Subscriber sub) {
        subscribers.remove(sub);
        if (sub.closed.compareAndSet(false, true)) {
            sub.queue.clear();
            sub.queue.offer(CLOSE);
            Thread t = sub.thread;
            if (t != null) {
                t.interrupt();
            }
        }
    }

    /**
     * Eventi da rinviare per {@code lastEventId}: i successivi se è nel buffer; tutto il buffer se ne è uscito da poco
     * (più di 200 persi: si rinviano gli ultimi 200, insight §3); nulla se l'id manca o non è mai stato visto qui.
     */
    // SPEC-GAP: Q-322 — Last-Event-ID mai visto da questa istanza (riavvio, altra istanza, id inventato): nessun
    // rinvio, per non duplicare eventi già visti; il client riparte dal vivo.
    private List<LiveEvent> replaySince(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return List.of();
        }
        List<LiveEvent> all = new ArrayList<>(recent);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).eventId().equals(lastEventId)) {
                return all.subList(i + 1, all.size());
            }
        }
        return evicted.contains(lastEventId) ? all : List.of();
    }

    @PreDestroy
    void shutdown() {
        heartbeats.shutdownNow();
        for (Subscriber sub : subscribers) {
            close(sub);
        }
        log.debug("LiveEventHub arrestato");
    }

    /** Un client collegato: filtro, coda limitata e thread d'invio proprio. */
    private final class Subscriber {
        final SseEmitter emitter;
        final Filter filter;
        final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(CLIENT_QUEUE);
        final AtomicBoolean closed = new AtomicBoolean();
        volatile Thread thread;

        Subscriber(SseEmitter emitter, Filter filter) {
            this.emitter = emitter;
            this.filter = filter;
        }

        void start() {
            thread = Thread.ofVirtual().name("lh-insight-sse").start(this::drain);
            if (closed.get()) {
                thread.interrupt();
            }
        }

        private void drain() {
            try {
                while (!closed.get()) {
                    Object item = queue.take();
                    if (item == CLOSE || closed.get()) {
                        break;
                    }
                    if (item == HEARTBEAT) {
                        emitter.send(SseEmitter.event().comment("hb"));
                    } else {
                        LiveEvent event = (LiveEvent) item;
                        emitter.send(SseEmitter.event()
                                .id(event.eventId())
                                .name("lh-event")
                                .data(event, MediaType.APPLICATION_JSON));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.debug("Invio SSE fallito, client rimosso: {}", e.toString());
            } finally {
                close(this);
                try {
                    emitter.complete();
                } catch (RuntimeException ignored) {
                    // già chiuso
                }
            }
        }
    }
}
