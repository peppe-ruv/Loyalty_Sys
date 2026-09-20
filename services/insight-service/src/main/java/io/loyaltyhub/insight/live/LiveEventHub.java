package io.loyaltyhub.insight.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

/**
 * Bus SSE in memoria (docs/servizi/insight-service.md §5, ADR-020): l'ingest pubblica qui ogni evento nuovo e
 * gli emitter connessi lo ricevono, filtrati per topic/tipo/membro/correlazione. Heartbeat ogni 15 s;
 * {@code Last-Event-ID} → rinvio degli ultimi ≤ 200 (ring buffer). Tutte le {@code send} passano da un solo
 * thread (serializzate, così non si corrompono e non bloccano il thread di ingest); un client rotto è rimosso.
 */
@Component
public class LiveEventHub {

    private static final Logger log = LoggerFactory.getLogger(LiveEventHub.class);
    private static final int REPLAY_BUFFER = 200;
    private static final long EMITTER_TIMEOUT_MS = 3_600_000L; // 1 h; il client riconnette (EventSource)

    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final Deque<LiveEvent> recent = new ArrayDeque<>();
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "lh-insight-sse");
                t.setDaemon(true);
                return t;
            });

    public LiveEventHub() {
        worker.scheduleAtFixedRate(this::heartbeat, 15, 15, TimeUnit.SECONDS);
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

    /** Apre uno stream SSE; se {@code lastEventId} è noto rinvia gli eventi persi dal ring buffer. */
    public SseEmitter subscribe(Filter filter, String lastEventId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        Subscriber sub = new Subscriber(emitter, filter);
        emitter.onCompletion(() -> subscribers.remove(sub));
        emitter.onTimeout(() -> {
            subscribers.remove(sub);
            emitter.complete();
        });
        emitter.onError(e -> subscribers.remove(sub));
        subscribers.add(sub);

        List<LiveEvent> backlog = replaySince(lastEventId);
        worker.execute(() -> {
            for (LiveEvent e : backlog) {
                if (filter.matches(e)) {
                    sendTo(sub, e);
                }
            }
        });
        return emitter;
    }

    /** Pubblica un evento nuovo: consegna asincrona ai soli emitter che lo vogliono. */
    public void publish(LiveEvent event) {
        synchronized (recent) {
            recent.addLast(event);
            while (recent.size() > REPLAY_BUFFER) {
                recent.removeFirst();
            }
        }
        worker.execute(() -> {
            for (Subscriber sub : subscribers) {
                if (sub.filter.matches(event)) {
                    sendTo(sub, event);
                }
            }
        });
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    private void heartbeat() {
        for (Subscriber sub : subscribers) {
            try {
                sub.emitter.send(SseEmitter.event().comment("hb"));
            } catch (Exception e) {
                drop(sub);
            }
        }
    }

    private void sendTo(Subscriber sub, LiveEvent event) {
        try {
            sub.emitter.send(SseEmitter.event()
                    .id(event.eventId())
                    .name("lh-event")
                    .data(event, MediaType.APPLICATION_JSON));
        } catch (IOException | RuntimeException e) {
            drop(sub);
        }
    }

    private void drop(Subscriber sub) {
        if (subscribers.remove(sub)) {
            try {
                sub.emitter.complete();
            } catch (RuntimeException ignored) {
                // già chiuso
            }
        }
    }

    private List<LiveEvent> replaySince(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return List.of();
        }
        synchronized (recent) {
            List<LiveEvent> all = new ArrayList<>(recent);
            int idx = -1;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).eventId().equals(lastEventId)) {
                    idx = i;
                    break;
                }
            }
            return idx < 0 ? all : all.subList(idx + 1, all.size());
        }
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
        for (Subscriber sub : subscribers) {
            try {
                sub.emitter.complete();
            } catch (RuntimeException ignored) {
                // chiusura best-effort
            }
        }
        log.debug("LiveEventHub arrestato");
    }

    private record Subscriber(SseEmitter emitter, Filter filter) {
    }
}
