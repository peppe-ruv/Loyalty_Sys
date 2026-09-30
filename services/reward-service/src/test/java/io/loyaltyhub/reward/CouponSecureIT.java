package io.loyaltyhub.reward;

import io.loyaltyhub.testsupport.ListenerGroups;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Codici coupon FUORI dalla demo (Q-614, F-CPN-01, ADR-044): nessun profilo {@code demo}, quindi i codici vengono da
 * {@code SecureRandom}, il seme non esiste (né nel pool, né nella risposta, né nell'audit) e due generazioni dello
 * stesso pool nello stesso stato di partenza danno codici diversi. Il gemello in demo è {@link CouponIT}.
 * Senza Docker: EmbeddedKafka + Zonky.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CouponSecureIT {

    private static final EmbeddedPostgres PG = startPg();

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=reward");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @BeforeAll
    void waitForListenerGroup() {
        ListenerGroups.awaitStable(listeners);
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    void generationIsUnpredictableAndLeavesNoSeedAnywhere() {
        String id = send("POST", "/v1/coupon-pools", "MARKETING:giulia",
                Map.of("code", "POOL-SEC-1", "name", "Pool non demo", "prefix", "SEC", "validityDays", 30), 201)
                .path("id").asString();
        assertThat(jdbc.sql("SELECT seed FROM coupon_pool WHERE id = ?").param(id).query(Long.class).single())
                .as("nessun seme registrato").isZero();

        JsonNode first = send("POST", "/v1/coupon-pools/" + id + "/generate", "MARKETING:giulia", Map.of("count", 200), 200);
        assertThat(first.path("generated").asInt()).isEqualTo(200);
        assertThat(first.has("seed")).as("nessun seme nella risposta: " + first).isFalse();
        List<String> firstCodes = codes(id);
        assertThat(new HashSet<>(firstCodes)).hasSize(200);
        assertThat(firstCodes).allSatisfy(c -> assertThat(c).matches("^SEC-[A-HJKMNP-Z2-9]{4}-[A-HJKMNP-Z2-9]{4}$"));

        // Stesso pool, stesso stato di partenza (0 codici): in demo darebbe la stessa sequenza, qui no.
        jdbc.sql("DELETE FROM coupon WHERE pool_id = ?").param(id).update();
        JsonNode second = send("POST", "/v1/coupon-pools/" + id + "/generate", "MARKETING:giulia", Map.of("count", 200), 200);
        assertThat(second.has("seed")).isFalse();
        List<String> secondCodes = codes(id);
        assertThat(secondCodes).hasSize(200).isNotEqualTo(firstCodes);
        assertThat(new HashSet<>(secondCodes).removeAll(new HashSet<>(firstCodes)))
                .as("nessun codice in comune tra le due generazioni").isFalse();

        // Audit (outbox → lh.audit.v1): la voce c'è, con il conteggio, ma senza alcun seme.
        List<String> audits = jdbc.sql("SELECT payload::text FROM outbox WHERE topic = 'lh.audit.v1' AND msg_key = ? ORDER BY created_at")
                .param("COUPON_POOL:POOL-SEC-1").query(String.class).list();
        List<String> generated = audits.stream().filter(p -> p.contains("Generati")).toList();
        assertThat(generated).hasSize(2);
        assertThat(generated).allSatisfy(p -> assertThat(p.toLowerCase()).doesNotContain("seed"));
        assertThat(generated).allSatisfy(p -> assertThat(p).contains("\"generated\""));
    }

    private List<String> codes(String poolId) {
        List<String> out = new ArrayList<>();
        get("/v1/coupon-pools/" + poolId + "/coupons?status=AVAILABLE&size=100").path("items")
                .forEach(c -> out.add(c.path("code").asString()));
        long total = get("/v1/coupon-pools/" + poolId + "/coupons?status=AVAILABLE&size=1").path("page").path("totalItems").asLong();
        for (int page = 1; out.size() < total; page++) {
            get("/v1/coupon-pools/" + poolId + "/coupons?status=AVAILABLE&size=100&page=" + page).path("items")
                    .forEach(c -> out.add(c.path("code").asString()));
        }
        return out.stream().sorted().toList();
    }

    private JsonNode get(String path) {
        return RestClient.create("http://localhost:" + port).get().uri(path).retrieve().body(JsonNode.class);
    }

    private JsonNode send(String method, String path, String actor, Object body, int expected) {
        var spec = RestClient.create("http://localhost:" + port).method(HttpMethod.valueOf(method))
                .uri(path).header("X-LH-Actor", actor);
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return spec.exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(res.getStatusCode().value()).as(method + " " + path + " → " + text).isEqualTo(expected);
            return text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text);
        });
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
