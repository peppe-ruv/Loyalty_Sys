package io.loyaltyhub.insight;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Attrezzi comuni dei test d'integrazione del testbook TB-INS (docs/testbook/TB-INS-insight.md, docs/16 §1bis):
 * chiamate HTTP con intestazione {@code X-LH-Actor}, pubblicazione su Kafka embedded, costruzione degli envelope
 * CloudEvents (docs/05 §2), attese con timeout (mai pause fisse come oracolo), lettore SSE e orologio fermo.
 * Nessuna annotazione Spring: le sottoclassi dichiarano il proprio contesto.
 */
abstract class TestbookInsSupport {

    static final String ACTION = "io.loyaltyhub.action.";
    static final String EFFECT = "io.loyaltyhub.effect.";
    static final String FACT = "io.loyaltyhub.fact.";
    static final String AUDIT_TYPE = "io.loyaltyhub.audit.entry";
    static final ZoneId ROME = ZoneId.of("Europe/Rome");

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static volatile KafkaProducer<String, String> producer;

    protected final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    protected int port;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected EmbeddedKafkaBroker broker;

    @Autowired
    protected io.loyaltyhub.insight.live.LiveEventHub liveHub;

    // ---------- attori (docs/06 §3, docs/08 §2) ----------

    /** Intestazione {@code X-LH-Actor} dall'etichetta di ruolo del CSV; {@code NONE} = assente. */
    static String actor(String label) {
        return switch (label) {
            case "NONE", "-" -> null;
            case "INVALID" -> "GUEST:ospite";
            case "LOWER" -> "admin:ada.admin";
            case "ADMIN" -> "ADMIN:ada.admin";
            case "MARKETING" -> "MARKETING:luca.marketing";
            case "LEGAL" -> "LEGAL:elena.legal";
            case "CARE" -> "CARE:carla.care";
            case "ANALYST" -> "ANALYST:andrea.analyst";
            default -> label;
        };
    }

    static String uid(String prefix) {
        return prefix + "-" + Long.toString(System.nanoTime(), 36).toUpperCase() + "-" + SEQ.incrementAndGet();
    }

    // ---------- HTTP ----------

    /** Risposta HTTP: stato, corpo JSON (nodo vuoto se assente o non JSON), testo grezzo. */
    record Resp(int status, JsonNode body, String text) {
        String code() {
            return body.path("code").asString("");
        }
    }

    Resp call(String method, String path, String actor, Object body) {
        return call(method, path, actor, body, Map.of());
    }

    Resp call(String method, String path, String actor, Object body, Map<String, String> headers) {
        try {
            String payload = body == null ? null : body instanceof String s ? s : mapper.writeValueAsString(body);
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(40));
            if (actor != null) {
                b.header("X-LH-Actor", actor);
            }
            headers.forEach(b::header);
            if (payload != null) {
                b.header("Content-Type", "application/json");
                b.method(method, HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode node;
            try {
                node = r.body() == null || r.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(r.body());
            } catch (RuntimeException e) {
                node = mapper.createObjectNode();
            }
            return new Resp(r.statusCode(), node, r.body());
        } catch (Exception e) {
            throw new IllegalStateException("Chiamata " + method + " " + path + " fallita", e);
        }
    }

    JsonNode get(String path) {
        Resp r = call("GET", path, null, null);
        if (r.status() != 200) {
            throw new AssertionError("GET " + path + " → " + r.status() + " " + r.text());
        }
        return r.body();
    }

    // ---------- Kafka ----------

    /** Produttore condiviso, ricreato se il broker embedded cambia (un contesto nuovo ha un broker nuovo). */
    KafkaProducer<String, String> producer() {
        String brokers = broker.getBrokersAsString();
        synchronized (TestbookInsSupport.class) {
            if (producer == null || !brokers.equals(producerBrokers)) {
                if (producer != null) {
                    producer.close(Duration.ofSeconds(2));
                }
                Properties props = new Properties();
                props.put("bootstrap.servers", brokers);
                props.put("linger.ms", "0");
                producer = new KafkaProducer<>(props, new StringSerializer(), new StringSerializer());
                producerBrokers = brokers;
            }
            return producer;
        }
    }

    private static volatile String producerBrokers;

    /** Pubblica e restituisce l'offset del record. */
    long publish(String topic, String key, String value, Map<String, String> headers, Long timestamp) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, null, timestamp, key, value);
        headers.forEach((k, v) -> record.headers().add(k, v.getBytes(StandardCharsets.UTF_8)));
        try {
            return producer().send(record).get(10, TimeUnit.SECONDS).offset();
        } catch (Exception e) {
            throw new IllegalStateException("Pubblicazione su " + topic + " fallita", e);
        }
    }

    long publish(String topic, ObjectNode envelope) {
        String subject = envelope.path("subject").asString("");
        String key = subject.startsWith("member:") ? subject.substring(7) : subject;
        return publish(topic, key, mapper.writeValueAsString(envelope), Map.of(), null);
    }

    static String topicOf(String type) {
        if (type.startsWith(ACTION)) {
            return "lh.actions.v1";
        }
        if (type.startsWith(EFFECT)) {
            return "lh.effects.v1";
        }
        if (type.startsWith(AUDIT_TYPE)) {
            return "lh.audit.v1";
        }
        return "lh.facts.v1";
    }

    /** Envelope CloudEvents 1.0 completo (docs/05 §2). {@code time} nullo = attributo assente. */
    ObjectNode envelope(String id, String type, String source, String subject, String correlationId,
                        String causationId, Instant time, Map<String, ?> data) {
        ObjectNode e = mapper.createObjectNode();
        e.put("specversion", "1.0");
        e.put("id", id);
        e.put("source", source);
        e.put("type", type);
        if (subject != null) {
            e.put("subject", subject);
        }
        if (time != null) {
            e.put("time", time.toString());
        }
        e.put("datacontenttype", "application/json");
        e.put("dataschema", "urn:loyaltyhub:schema:test:1");
        e.put("lhtenant", "aurora");
        if (correlationId != null) {
            e.put("lhcorrelationid", correlationId);
        }
        if (causationId != null) {
            e.put("lhcausationid", causationId);
        }
        e.put("lhhop", 0);
        e.put("lhactor", "system");
        e.set("data", data == null ? tools.jackson.databind.node.NullNode.getInstance() : mapper.valueToTree(data));
        return e;
    }

    /** Dati {@code k=v;k2=v2} del CSV (numeri interi riconosciuti, {@code -} = nessun dato). */
    static Map<String, Object> kv(String spec) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (spec == null || spec.isBlank() || spec.equals("-")) {
            return out;
        }
        for (String part : spec.split(";")) {
            int eq = part.indexOf('=');
            String k = part.substring(0, eq).trim();
            String v = part.substring(eq + 1).trim();
            out.put(k, v.matches("-?\\d+") ? (Object) Long.valueOf(v) : v);
        }
        return out;
    }

    // ---------- attese ----------

    static void await(String what, BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            pause(100);
        }
        if (!condition.getAsBoolean()) {
            throw new AssertionError("Attesa scaduta dopo " + timeoutMs + " ms: " + what);
        }
    }

    static <T> T awaitValue(String what, Supplier<T> supplier, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            T v = supplier.get();
            if (v != null) {
                return v;
            }
            pause(100);
        }
        T v = supplier.get();
        if (v == null) {
            throw new AssertionError("Attesa scaduta dopo " + timeoutMs + " ms: " + what);
        }
        return v;
    }

    /** Pausa breve del ciclo di attesa (mai usata come oracolo). */
    static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    boolean eventStored(String eventId) {
        return call("GET", "/v1/events/" + eventId, null, null).status() == 200;
    }

    void awaitStored(String eventId) {
        await("evento " + eventId + " nell'event store", () -> eventStored(eventId), 20_000);
    }

    void setReceivedAt(String eventId, Instant at) {
        jdbc.sql("UPDATE event_store SET received_at = ? WHERE event_id = ?")
                .params(Timestamp.from(at), eventId).update();
    }

    // ---------- SSE ----------

    /** Un evento SSE ricevuto: {@code id}, {@code event}, {@code data} (JSON). */
    record SseEvent(String id, String event, String data) {
    }

    /** Lettore SSE su {@code /v1/stream/events}: eventi e commenti ({@code :hb}) in code bloccanti. */
    final class SseClient implements AutoCloseable {
        final BlockingQueue<SseEvent> events = new LinkedBlockingQueue<>();
        final List<String> comments = new CopyOnWriteArrayList<>();
        private final java.util.concurrent.CompletableFuture<HttpResponse<InputStream>> future;
        private volatile Thread reader;

        /**
         * Apre il canale senza attendere le intestazioni (Spring le invia col primo messaggio, che può essere il
         * heartbeat dopo 15 s): si attende invece che l'iscrizione compaia sul bus ({@code subscriberCount}).
         */
        SseClient(String query, String lastEventId, Map<String, String> headers) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/v1/stream/events" + (query == null ? "" : query)))
                    .header("Accept", "text/event-stream").GET();
            if (lastEventId != null) {
                b.header("Last-Event-ID", lastEventId);
            }
            headers.forEach(b::header);
            int before = liveHub.subscriberCount();
            future = HTTP.sendAsync(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            future.thenAccept(r -> {
                Thread t = new Thread(() -> read(r), "tb-ins-sse");
                t.setDaemon(true);
                reader = t;
                t.start();
            });
            long deadline = System.currentTimeMillis() + 10_000;
            while (!future.isDone() && liveHub.subscriberCount() <= before && System.currentTimeMillis() < deadline) {
                pause(20);
            }
            pause(50);
        }

        /** Risposta HTTP (attende le intestazioni). */
        HttpResponse<InputStream> response() throws Exception {
            return future.get(20, TimeUnit.SECONDS);
        }

        private void read(HttpResponse<InputStream> response) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                String id = null;
                String name = null;
                StringBuilder data = new StringBuilder();
                while ((line = br.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (data.length() > 0 || name != null) {
                            events.add(new SseEvent(id, name, data.toString()));
                        }
                        id = null;
                        name = null;
                        data.setLength(0);
                    } else if (line.startsWith(":")) {
                        comments.add(line);
                    } else if (line.startsWith("id:")) {
                        id = line.substring(3).trim();
                    } else if (line.startsWith("event:")) {
                        name = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        data.append(line.substring(5));
                    }
                }
            } catch (Exception ignored) {
                // stream chiuso
            }
        }

        /** Raccoglie eventi finché non ne arrivano {@code n} o scade il tempo. */
        List<SseEvent> take(int n, long timeoutMs) throws InterruptedException {
            List<SseEvent> out = new ArrayList<>();
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (out.size() < n) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                SseEvent e = events.poll(left, TimeUnit.MILLISECONDS);
                if (e != null) {
                    out.add(e);
                }
            }
            return out;
        }

        /** Eventi arrivati entro {@code quietMs} (per provare che non ne arrivano altri). */
        List<SseEvent> drain(long quietMs) throws InterruptedException {
            List<SseEvent> out = new ArrayList<>();
            SseEvent e;
            while ((e = events.poll(quietMs, TimeUnit.MILLISECONDS)) != null) {
                out.add(e);
            }
            return out;
        }

        @Override
        public void close() {
            if (!future.isDone()) {
                future.cancel(true);
            }
            try {
                if (future.isDone() && !future.isCancelled()) {
                    future.get().body().close();
                }
            } catch (Exception ignored) {
                // già chiuso
            }
            Thread t = reader;
            if (t != null) {
                t.interrupt();
            }
        }
    }

    // ---------- orologio ----------

    /** Orologio dell'applicazione: fermo quando un caso lo imposta, altrimenti quello di sistema. */
    static final class TestbookClock extends Clock {
        private volatile Instant fixed;

        void set(Instant at) {
            fixed = at;
        }

        void reset() {
            fixed = null;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            TestbookClock self = this;
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return zone;
                }

                @Override
                public Clock withZone(ZoneId z) {
                    return self.withZone(z);
                }

                @Override
                public Instant instant() {
                    return self.instant();
                }
            };
        }

        @Override
        public Instant instant() {
            Instant f = fixed;
            return f != null ? f : Instant.now();
        }
    }

    static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
