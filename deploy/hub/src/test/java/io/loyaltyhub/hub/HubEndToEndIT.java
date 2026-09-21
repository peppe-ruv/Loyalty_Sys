package io.loyaltyhub.hub;

import tools.jackson.databind.JsonNode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova end-to-end del deployable <strong>consolidato</strong> (docs/13 ADR-023): i 4 servizi girano in un
 * solo JVM e il core loop funziona per intero — {@code POST /v1/events} → campaign valuta → wallet accredita
 * col moltiplicatore di tier → saldo del portale aggiornato. Senza Docker: EmbeddedKafka + Zonky, profilo demo.
 */
@SpringBootTest(
        classes = HubApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.name=hub")
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HubEndToEndIT {

    private static final EmbeddedPostgres PG = startPg();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        // Un solo database, search_path con i 4 schemi (ADR-023).
        registry.add("spring.datasource.url", () -> base + "&currentSchema=ingestion,member,campaign,wallet,insight");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    void purchaseTravelsThroughAllServicesAndCreditsPoints() {
        // MBR-000003 è SILVER (×1,25), saldo seed 3240 PTS: acquisto 130 € → +162 → 3402.
        long before = walletPts("MBR-000003");
        assertThat(before).as("il wallet è seminato dal profilo demo").isGreaterThan(0);

        // Istante feriale fisso (martedì) per un risultato deterministico: niente moltiplicatore weekend.
        Map<String, Object> event = Map.of(
                "specversion", "1.0", "id", "hub-e2e-01", "source", "urn:loyaltyhub:source:ecommerce",
                "type", "purchase.completed", "subject", "member:MBR-000003",
                "time", "2026-09-15T10:00:00Z",
                "data", Map.of("orderId", "ORD-HUB-1", "amount", 130, "currency", "EUR", "channel", "ONLINE"));
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
        assertThat(after).as("acquisto 130€ SILVER → +162 PTS attraverso ingestion→campaign→wallet")
                .isEqualTo(before + 162);
    }

    @Test
    void demoResetIsExposedForAllServices() {
        JsonNode body = client().post().uri("/v1/demo/reset").header("X-LH-Actor", "ADMIN:test")
                .retrieve().body(JsonNode.class);
        assertThat(body.path("status").asString()).isEqualTo("OK");
        assertThat(body.path("reset").size()).as("tutti i seeder dei 4 servizi").isGreaterThanOrEqualTo(4);
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
