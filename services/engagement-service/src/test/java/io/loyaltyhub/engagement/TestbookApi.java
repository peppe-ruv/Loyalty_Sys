package io.loyaltyhub.engagement;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Accesso del testbook TB-ENG alle API del servizio (RestClient sulla porta casuale), a Kafka (produttore unico per la
 * classe) e al database, con attese a polling e scadenza al posto delle pause fisse.
 */
public final class TestbookApi implements AutoCloseable {

    /** Risposta HTTP: stato, corpo JSON (vuoto se assente) e intestazioni. */
    public record Resp(int status, JsonNode body, HttpHeaders headers) {
        public String code() {
            return body.path("code").asString("");
        }

        /** Campi degli errori RFC 9457 ({@code errors[].field}). */
        public List<String> fields() {
            List<String> out = new ArrayList<>();
            body.path("errors").forEach(e -> out.add(e.path("field").asString()));
            return out;
        }
    }

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient http;
    private final JdbcClient jdbc;
    private KafkaProducer<String, String> producer;

    public TestbookApi(int port, JdbcClient jdbc) {
        this.http = RestClient.create("http://localhost:" + port);
        this.jdbc = jdbc;
    }

    public Resp send(String method, String path, String actor, Object body) {
        var spec = http.method(HttpMethod.valueOf(method)).uri(path);
        if (actor != null) {
            spec = spec.header("X-LH-Actor", actor);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body instanceof String s ? s : MAPPER.writeValueAsString(body));
        }
        return spec.exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode json = text.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(text);
            return new Resp(res.getStatusCode().value(), json, res.getHeaders());
        });
    }

    public Resp get(String path) {
        return send("GET", path, null, null);
    }

    /** Come {@link #send} ma pretende lo stato atteso (messaggio con il corpo in caso contrario). */
    public JsonNode ok(String method, String path, String actor, Object body, int expected) {
        Resp r = send(method, path, actor, body);
        if (r.status() != expected) {
            throw new AssertionError(method + " " + path + " → " + r.status() + " (atteso " + expected + "): " + r.body());
        }
        return r.body();
    }

    // ---------- Kafka ----------

    /** CloudEvent minimo di un fatto o di un effetto per il membro. */
    public static Map<String, Object> event(String id, String type, String memberId, Map<String, Object> data) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("specversion", "1.0");
        e.put("id", id);
        e.put("source", "urn:loyaltyhub:service:testbook");
        e.put("type", type);
        e.put("subject", "member:" + memberId);
        e.put("time", Instant.now().toString());
        e.put("datacontenttype", "application/json");
        e.put("lhtenant", "aurora");
        e.put("lhcorrelationid", "CORR-" + id);
        e.put("lhhop", 0);
        e.put("data", data);
        return e;
    }

    public void publish(String topic, String key, Map<String, Object> event) {
        if (producer == null) {
            producer = new KafkaProducer<>(Map.of("bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                    "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class));
        }
        try {
            producer.send(new ProducerRecord<>(topic, key, MAPPER.writeValueAsString(event))).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- database e attese ----------

    public long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    /** Attende che l'evento sia stato consumato (riga in {@code processed_event}). */
    public void awaitProcessed(String eventId) {
        await(() -> count("SELECT count(*) FROM processed_event WHERE event_id = ?", eventId) > 0, "evento non elaborato: " + eventId);
    }

    public static void await(BooleanSupplier condition, String message) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(message, e);
            }
        }
        throw new AssertionError(message);
    }

    @Override
    public void close() {
        if (producer != null) {
            producer.close();
        }
    }
}
