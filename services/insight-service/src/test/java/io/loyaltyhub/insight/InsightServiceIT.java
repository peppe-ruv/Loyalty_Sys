package io.loyaltyhub.insight;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * insight-service M2.1 (docs/servizi/insight-service.md §7): ingest di tutti i topic nell'event store,
 * idempotenza su {@code event_id}, retention. Senza Docker: EmbeddedKafka + Zonky, profilo demo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InsightServiceIT {

    private static final EmbeddedPostgres PG = startPg();

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=insight");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    void storesEventsFromAllTopicsWithFamilyAndIsIdempotent() {
        String corr = "COR-INS-1";
        publish("lh.actions.v1", envelope("EVT-A1", "io.loyaltyhub.action.purchase.completed",
                "urn:loyaltyhub:source:ecommerce", "member:MBR-000003", corr));
        publish("lh.effects.v1", envelope("EVT-E1", "io.loyaltyhub.effect.points.grant",
                "urn:loyaltyhub:service:campaign", "member:MBR-000003", corr));
        publish("lh.facts.v1", envelope("EVT-F1", "io.loyaltyhub.fact.wallet.points.earned",
                "urn:loyaltyhub:service:wallet", "member:MBR-000003", corr));
        // Duplicato dell'azione: non deve creare una seconda riga.
        publish("lh.actions.v1", envelope("EVT-A1", "io.loyaltyhub.action.purchase.completed",
                "urn:loyaltyhub:source:ecommerce", "member:MBR-000003", corr));

        JsonNode page = awaitCount(corr, 3);
        assertThat(page.path("count").asInt()).isEqualTo(3);

        // L'azione è registrata con la famiglia del topic e lo short type senza prefisso.
        JsonNode detail = client().get().uri("/v1/events/EVT-A1").retrieve().body(JsonNode.class);
        assertThat(detail.path("family").asString()).isEqualTo("ACTION");
        assertThat(detail.path("shortType").asString()).isEqualTo("purchase.completed");
        assertThat(detail.path("memberId").asString()).isEqualTo("MBR-000003");
        assertThat(detail.path("payload").path("data").path("amount").asInt()).isEqualTo(130);

        // L'idempotenza (duplicato non raddoppiato) è già provata sopra: la pagina per COR-INS-1 ha 3 righe,
        // non 4. La statistica del topic azioni esiste ed è positiva (il valore globale dipende dagli altri test).
        JsonNode pipeline = client().get().uri("/v1/pipeline/status").retrieve().body(JsonNode.class);
        long actionCount = 0;
        for (JsonNode t : pipeline.path("topics")) {
            if (t.path("topic").asString().equals("lh.actions.v1")) {
                actionCount = t.path("countTotal").asLong();
            }
        }
        assertThat(actionCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    void demoResetClearsTheStore() {
        publish("lh.facts.v1", envelope("EVT-R1", "io.loyaltyhub.fact.wallet.points.earned",
                "urn:loyaltyhub:service:wallet", "member:MBR-000009", "COR-RST"));
        awaitCount("COR-RST", 1);

        JsonNode body = client().post().uri("/v1/demo/reset").header("X-LH-Actor", "ADMIN:test")
                .retrieve().body(JsonNode.class);
        assertThat(body.path("status").asString()).isEqualTo("OK");

        JsonNode page = client().get().uri("/v1/events?correlationId=COR-RST").retrieve().body(JsonNode.class);
        assertThat(page.path("count").asInt()).isEqualTo(0);
    }

    @Test
    void streamDeliversLiveEventOverSse() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/v1/stream/events?topics=lh.actions.v1"))
                .header("Accept", "text/event-stream").GET().build();

        // send() torna appena arrivano gli header: da qui l'emitter è registrato sul LiveEventHub.
        HttpResponse<java.io.InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("content-type").orElse("")).contains("text/event-stream");

        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    lines.offer(line);
                }
            } catch (Exception ignored) {
                // stream chiuso a fine test
            }
        });
        reader.setDaemon(true);
        reader.start();

        publish("lh.actions.v1", envelope("EVT-SSE-1", "io.loyaltyhub.action.purchase.completed",
                "urn:loyaltyhub:source:ecommerce", "member:MBR-000003", "COR-SSE"));

        StringBuilder acc = new StringBuilder();
        boolean seen = false;
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            String line = lines.poll(500, TimeUnit.MILLISECONDS);
            if (line != null) {
                acc.append(line).append('\n');
                if (acc.indexOf("lh-event") >= 0 && acc.indexOf("EVT-SSE-1") >= 0) {
                    seen = true;
                    break;
                }
            }
        }
        reader.interrupt();
        assertThat(seen).as("l'evento arriva sul canale SSE con nome lh-event").isTrue();
    }

    // ---------- helper ----------

    private JsonNode awaitCount(String correlationId, int expected) {
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode page = null;
        while (System.currentTimeMillis() < deadline) {
            page = client().get().uri("/v1/events?correlationId=" + correlationId).retrieve().body(JsonNode.class);
            if (page != null && page.path("count").asInt() >= expected) {
                return page;
            }
            sleep();
        }
        return page;
    }

    private String envelope(String id, String type, String source, String subject, String correlationId) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("specversion", "1.0");
        env.put("id", id);
        env.put("source", source);
        env.put("type", type);
        env.put("subject", subject);
        env.put("time", "2026-09-15T10:00:00Z");
        env.put("lhcorrelationid", correlationId);
        env.put("lhcausationid", correlationId);
        env.put("lhhop", 0);
        env.put("lhactor", "system");
        env.put("data", Map.of("orderId", "ORD-1", "amount", 130, "currency", "EUR"));
        return mapper.writeValueAsString(env);
    }

    private void publish(String topic, String value) {
        Properties props = new Properties();
        props.put("bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"));
        try (KafkaProducer<String, String> producer =
                     new KafkaProducer<>(props, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(topic, "MBR-000003", value));
            producer.flush();
        }
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private static void sleep() {
        try {
            Thread.sleep(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
