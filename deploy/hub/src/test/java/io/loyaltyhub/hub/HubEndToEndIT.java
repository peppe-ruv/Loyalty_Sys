package io.loyaltyhub.hub;

import io.loyaltyhub.ingestion.domain.ScenarioTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova end-to-end del deployable <strong>consolidato</strong> (docs/13 ADR-023): i 4 servizi girano in un
 * solo JVM e il core loop funziona per intero — {@code POST /v1/events} → campaign valuta → wallet accredita
 * col moltiplicatore di tier → saldo del portale aggiornato. Più i criteri di accettazione M3 che attraversano più
 * servizi (docs/12): {@code SCN-TIER-UP} col ponte interno e il job scadenze. Senza Docker: EmbeddedKafka + Zonky.
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
        registry.add("spring.datasource.url", () -> base + "&currentSchema=ingestion,member,campaign,wallet,insight,reward");
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
        // MBR-000002 (Marco) è SILVER (×1,25) e resta SILVER (1 420 + 130 STS < 3 000): acquisto 130 € → +162 PTS.
        long before = walletPts("MBR-000002");
        assertThat(before).as("il wallet è seminato dal profilo demo").isGreaterThan(0);

        // Ultimo giorno feriale alle 10:00: niente moltiplicatore weekend e sempre dentro la finestra dei 30 giorni.
        String weekday = ScenarioTime.resolve("@lastWeekdayT10:00", Instant.now()).toString();
        Map<String, Object> event = Map.of(
                "specversion", "1.0", "id", "hub-e2e-01", "source", "urn:loyaltyhub:source:ecommerce",
                "type", "purchase.completed", "subject", "member:MBR-000002", "time", weekday,
                "data", Map.of("orderId", "ORD-HUB-1", "amount", 130, "currency", "EUR", "channel", "ONLINE"));
        JsonNode accepted = client().post().uri("/v1/events")
                .contentType(MediaType.APPLICATION_JSON).body(event).retrieve().body(JsonNode.class);
        assertThat(accepted.path("status").asString()).isEqualTo("ACCEPTED");

        assertThat(awaitPts("MBR-000002", before + 162))
                .as("acquisto 130€ SILVER → +162 PTS attraverso ingestion→campaign→wallet").isEqualTo(before + 162);
    }

    // ---------- accettazione M3 (docs/12) ----------

    @Test
    void scnTierUpBridgesTheUpgradeIntoTheBonusInOneTrace() {
        // Giulia (MBR-000003) SILVER con 2 880 STS: 130 € → +162 PTS e +130 STS → GOLD → tier.upgraded rientra come
        // azione interna (lhhop 1) → CMP-TIER-UP-BONUS +500 PTS. Tutto nello stesso tracciato.
        long before = walletPts("MBR-000003");
        JsonNode started = client().post().uri("/v1/demo/scenarios/SCN-TIER-UP/run")
                .header("X-LH-Actor", "ADMIN:test").retrieve().body(JsonNode.class);
        JsonNode run = awaitRunDone(started.path("runId").asString());
        JsonNode step = run.path("results").get(0);
        assertThat(step.path("status").asString()).isEqualTo("ACCEPTED");
        String correlationId = step.path("correlationId").asString();

        assertThat(awaitPts("MBR-000003", before + 162 + 500)).as("acquisto + bonus di livello").isEqualTo(before + 662);
        JsonNode wallet = client().get().uri("/v1/portal/wallets/MBR-000003").retrieve().body(JsonNode.class);
        assertThat(wallet.path("tier").path("code").asString()).isEqualTo("GOLD");

        JsonNode trace = awaitTrace(correlationId, t -> t.path("outcome").path("points").toString().contains("662"));
        long roots = 0;
        boolean bridgedAction = false;
        for (JsonNode n : trace.path("nodes")) {
            if (n.path("parentEventId").isNull() || n.path("parentEventId").isMissingNode()) {
                roots++;
            }
            if (n.path("family").asString().equalsIgnoreCase("action") && n.path("shortType").asString().equals("tier.upgraded")) {
                bridgedAction = true;
            }
        }
        assertThat(roots).as("un solo albero").isEqualTo(1);
        assertThat(bridgedAction).as("azione tier.upgraded dal ponte nello stesso tracciato").isTrue();
        assertThat(trace.path("outcome").path("tierChange").path("to").asString()).isEqualTo("GOLD");
    }

    @Test
    void expiryJobAtPlus31DaysExpiresChiarasPoints() {
        // Chiara (MBR-000007): 1 900 PTS in scadenza entro 30 giorni (docs/10). Job con asOf = oggi + 31 giorni.
        long before = walletPts("MBR-000007");
        String asOf = LocalDate.now(ZoneId.of("Europe/Rome")).plusDays(31).toString();
        client().post().uri("/v1/demo/jobs/expire-points?asOf=" + asOf)
                .header("X-LH-Actor", "ADMIN:test").retrieve().body(JsonNode.class);
        assertThat(walletPts("MBR-000007")).isEqualTo(before - 1900);

        JsonNode ledger = client().get().uri("/v1/wallets/MBR-000007/ledger").retrieve().body(JsonNode.class);
        assertThat(ledger.toString()).contains("EXPIRE").contains("1900");
    }

    @Test
    void demoResetIsExposedForAllServices() {
        JsonNode body = client().post().uri("/v1/demo/reset").header("X-LH-Actor", "ADMIN:test")
                .retrieve().body(JsonNode.class);
        assertThat(body.path("status").asString()).isEqualTo("OK");
        assertThat(body.path("reset").size()).as("tutti i seeder dei 4 servizi").isGreaterThanOrEqualTo(4);
    }

    @Test
    void rewardCatalogIsServedByTheHubWithItsOwnSnapshot() {
        // Schema reward nel search_path condiviso: lo snapshot dei membri è reward_member_snapshot, non quello di
        // campaign. Marco (SILVER) vede il weekend (F5) bloccato per tier.
        JsonNode catalog = client().get().uri("/v1/portal/catalog?memberId=MBR-000002").retrieve().body(JsonNode.class);
        assertThat(catalog.path("bands").size()).isEqualTo(5);
        assertThat(catalog.toString()).contains("RWD-WEEKEND").contains("lockedByTier");
    }

    // ---------- helper ----------

    private long awaitPts(String memberId, long expected) {
        long deadline = System.currentTimeMillis() + 30_000;
        long value = walletPts(memberId);
        while (value != expected && System.currentTimeMillis() < deadline) {
            sleep();
            value = walletPts(memberId);
        }
        return value;
    }

    private JsonNode awaitRunDone(String runId) {
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode run = null;
        while (System.currentTimeMillis() < deadline) {
            run = client().get().uri("/v1/demo/scenario-runs/" + runId).retrieve().body(JsonNode.class);
            if (!run.path("status").asString().equals("RUNNING")) {
                return run;
            }
            sleep();
        }
        return run;
    }

    private JsonNode awaitTrace(String correlationId, java.util.function.Predicate<JsonNode> done) {
        long deadline = System.currentTimeMillis() + 30_000;
        JsonNode trace = null;
        while (System.currentTimeMillis() < deadline) {
            trace = client().get().uri("/v1/traces/" + correlationId)
                    .exchange((req, res) -> res.getStatusCode().value() == 200 ? new tools.jackson.databind.ObjectMapper().readTree(res.getBody()) : null);
            if (trace != null && done.test(trace)) {
                return trace;
            }
            sleep();
        }
        assertThat(trace).as("tracciato " + correlationId).isNotNull();
        return trace;
    }

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
