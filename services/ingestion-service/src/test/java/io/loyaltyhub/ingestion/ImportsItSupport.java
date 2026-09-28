package io.loyaltyhub.ingestion;

import io.loyaltyhub.common.testsupport.TopicReader;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chiamate e attese comuni ai test d'integrazione dell'ingresso batch e dell'import file (M8.7, F2-ING-01/02): ogni
 * sottoclasse porta il proprio contesto (Postgres in-process ed EmbeddedKafka).
 */
abstract class ImportsItSupport {

    protected static final String ACTIONS = "lh.actions.v1";
    protected static final String ECOM = "urn:loyaltyhub:source:ecommerce";
    protected static final String ADMIN = "ADMIN:marta.admin";

    @Value("${local.server.port}")
    protected int port;
    @Autowired
    protected ObjectMapper mapper;
    @Autowired
    protected JdbcClient jdbc;

    // ================= helper =================

    protected void insertRunning(String id, String content, int rowsDone, int attempts, Instant heartbeat) {
        jdbc.sql("""
                        INSERT INTO import_job (id, kind, format, file_name, size_bytes, sha256, status, rows_total,
                          rows_done, accepted, attempts, content, created_by, created_at, started_at, heartbeat_at)
                        VALUES (?, 'EVENTS', 'NDJSON', 'ripresa.ndjson', ?, 'x', 'RUNNING', 2, ?, ?, ?, ?, 'ADMIN:test', ?, ?, ?)
                        """)
                .params(id, content.length(), rowsDone, rowsDone, attempts, content, Timestamp.from(heartbeat),
                        Timestamp.from(heartbeat), Timestamp.from(heartbeat))
                .update();
    }

    protected long inboundCount(String eventId) {
        return jdbc.sql("SELECT count(*) FROM inbound_event WHERE event_id = ?").param(eventId).query(Long.class).single();
    }

    protected JsonNode awaitFinished(String id) {
        long deadline = System.currentTimeMillis() + 30_000;
        JsonNode d = null;
        while (System.currentTimeMillis() < deadline) {
            d = get("/v1/imports/" + id);
            String s = d.path("job").path("status").asString();
            if (s.equals("DONE") || s.equals("FAILED")) {
                return d;
            }
            sleep(200);
        }
        throw new AssertionError("import " + id + " non concluso: " + d);
    }

    protected JsonNode upload(String name, String contentType, String text, String source, String actor, String key, int expected) {
        return uploadBytes(name, contentType, text.getBytes(StandardCharsets.UTF_8), source, actor, key, expected);
    }

    protected JsonNode uploadBytes(String name, String contentType, byte[] bytes, String source, String actor, String key,
                                 int expected) {
        return send(parts(name, contentType, bytes, source), actor, key, expected);
    }

    protected static MultiValueMap<String, Object> parts(String name, String contentType, byte[] bytes, String source) {
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(contentType));
        fileHeaders.setContentDispositionFormData("file", name);
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new HttpEntity<>(bytes, fileHeaders));
        if (source != null) {
            parts.add("source", source);
        }
        return parts;
    }

    protected JsonNode send(MultiValueMap<String, Object> parts, String actor, String key, int expected) {
        RestClient.RequestBodySpec spec = client().post().uri("/v1/imports").contentType(MediaType.MULTIPART_FORM_DATA)
                .header("X-LH-Actor", actor);
        if (key != null) {
            spec = spec.header("Idempotency-Key", key);
        }
        return spec.body(parts).exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(res.getStatusCode().value()).as(text).isEqualTo(expected);
            return mapper.readTree(text);
        });
    }

    protected JsonNode postJson(String path, Object body, String actor, int expected) {
        RestClient.RequestBodySpec spec = client().post().uri(path).contentType(MediaType.APPLICATION_JSON);
        if (actor != null) {
            spec = spec.header("X-LH-Actor", actor);
        }
        return spec.body(body).exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(res.getStatusCode().value()).as(text).isEqualTo(expected);
            return mapper.readTree(text);
        });
    }

    protected JsonNode post(String path, String actor, int expected) {
        return client().post().uri(path).header("X-LH-Actor", actor).exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(res.getStatusCode().value()).as(text).isEqualTo(expected);
            return mapper.readTree(text);
        });
    }

    protected JsonNode get(String path) {
        return get(path, 200);
    }

    protected JsonNode get(String path, int expected) {
        return client().get().uri(path).exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(res.getStatusCode().value()).as(text).isEqualTo(expected);
            return mapper.readTree(text);
        });
    }

    protected RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    protected Map<String, Object> purchase(String id, String subject) {
        Map<String, Object> event = new HashMap<>();
        event.put("specversion", "1.0");
        event.put("id", id);
        event.put("source", ECOM);
        event.put("type", "purchase.completed");
        event.put("subject", subject);
        event.put("time", Instant.now().toString());
        event.put("data", Map.of("orderId", "ORD-" + id, "amount", 130, "currency", "EUR", "channel", "ONLINE"));
        return event;
    }

    protected List<ConsumerRecord<String, String>> drain(Predicate<ConsumerRecord<String, String>> match) {
        return drain(ACTIONS, match);
    }

    /**
     * Tutti i record del topic che soddisfano {@code match}, esatti ({@link TopicReader}): le chiamate e i lavori
     * attesi dal test hanno già fatto commit, quindi basta leggere il topic dall'inizio fino in fondo a outbox svuotato,
     * senza consumer group né finestra di tempo. Un doppione resta visibile, un record assente è davvero assente.
     */
    protected List<ConsumerRecord<String, String>> drain(String topic, Predicate<ConsumerRecord<String, String>> match) {
        return new TopicReader(jdbc, mapper, topic).records(List.of(), match);
    }

    protected JsonNode readJson(String value) {
        return mapper.readTree(value);
    }

    protected static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Postgres in-process (Zonky) per una classe di test. */
    protected static EmbeddedPostgres startEmbeddedPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
