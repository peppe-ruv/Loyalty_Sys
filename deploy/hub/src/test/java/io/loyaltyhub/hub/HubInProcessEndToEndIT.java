package io.loyaltyhub.hub;

import tools.jackson.databind.JsonNode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova end-to-end della demo ospitata a costo zero (docs/13 ADR-024): profilo {@code inproc}, <strong>nessun
 * broker Kafka</strong>. Lo stesso acquisto del test col broker (130 € feriale, SILVER ×1,25 → +162 PTS)
 * attraversa ingestion→campaign→wallet passando dal {@link io.loyaltyhub.hub.bus.HubInProcessBus} invece che da
 * Redpanda. Se questo è verde, la demo ospitata gira su un solo web service gratuito senza broker a pagamento.
 */
@SpringBootTest(
        classes = HubApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.name=hub")
@ActiveProfiles({"demo", "inproc"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HubInProcessEndToEndIT {

    private static final EmbeddedPostgres PG = startPg();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=ingestion,member,campaign,wallet");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        // Nessun spring.kafka.bootstrap-servers: nel profilo inproc non c'è broker.
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    void purchaseTravelsThroughAllServicesWithoutABroker() {
        long before = walletPts("MBR-000003");
        assertThat(before).as("il wallet è seminato dal profilo demo").isGreaterThan(0);

        // Istante feriale fisso: risultato deterministico, niente moltiplicatore weekend.
        Map<String, Object> event = Map.of(
                "specversion", "1.0", "id", "hub-inproc-01", "source", "urn:loyaltyhub:source:ecommerce",
                "type", "purchase.completed", "subject", "member:MBR-000003",
                "time", "2026-09-15T10:00:00Z",
                "data", Map.of("orderId", "ORD-INPROC-1", "amount", 130, "currency", "EUR", "channel", "ONLINE"));
        JsonNode accepted = client().post().uri("/v1/events")
                .contentType(MediaType.APPLICATION_JSON).body(event).retrieve().body(JsonNode.class);
        assertThat(accepted.path("status").asString()).isEqualTo("ACCEPTED");

        long deadline = System.currentTimeMillis() + 25_000;
        long after = before;
        while (System.currentTimeMillis() < deadline) {
            after = walletPts("MBR-000003");
            if (after == before + 162) {
                break;
            }
            sleep();
        }
        assertThat(after).as("acquisto 130€ SILVER → +162 PTS via bus in-process (senza Kafka)")
                .isEqualTo(before + 162);
    }

    // ---------- helper ----------

    private long walletPts(String memberId) {
        JsonNode w = client().get().uri("/v1/portal/wallets/" + memberId).retrieve().body(JsonNode.class);
        return w.path("balances").path("PTS").path("active").asLong();
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private static void sleep() {
        try {
            Thread.sleep(500);
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
