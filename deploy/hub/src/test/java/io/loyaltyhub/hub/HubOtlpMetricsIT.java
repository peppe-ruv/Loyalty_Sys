package io.loyaltyhub.hub;

import com.sun.net.httpserver.HttpServer;
import io.loyaltyhub.common.metrics.LhMetrics;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le metriche arrivano davvero in OTLP (M8.6a, F2-OBS-01, ADR-012, Q-520). L'hub parte completo (profilo
 * {@code demo,inproc}, Postgres incorporato) con {@code LH_OTEL_METRICS_ENABLED=true} e passo di 1 s; un ricevitore
 * OTLP finto (HTTP su porta effimera di loopback) conserva i corpi ricevuti. Si verifica che il primo giro dei dati
 * contenga le metriche HTTP del server, quella custom di {@link LhMetrics}, lo SLI azione → punti di insight
 * ({@code lh_action_to_points_seconds}) e gli attributi della risorsa, e
 * che il formato sia protobuf. Prova, con l'applicazione vera, che {@code lh_*} (registrate sul
 * {@code SimpleMeterRegistry} di lh-common) non restano fuori da OTLP: lo stesso ordine dei bean del deploy reale.
 */
@SpringBootTest(
        classes = HubApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.name=hub")
@ActiveProfiles({"demo", "inproc"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// Il ricevitore finto si ferma in @AfterAll: senza chiudere il contesto (che Spring tiene in cache) il registro OTLP
// continuerebbe a pubblicare ogni secondo su una porta chiusa (WARN "Failed to publish metrics") per il resto
// dell'esecuzione di failsafe. Con @DirtiesContext si chiude a fine classe: al più un ultimo invio alla chiusura.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HubOtlpMetricsIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final Queue<Received> RECEIVED = new ConcurrentLinkedQueue<>();
    private static final HttpServer OTLP = startOtlpStub();

    private record Received(String contentType, byte[] body) {
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ApplicationContext context;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=ingestion,member,campaign,wallet,insight,reward,gamification,engagement");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("LH_OTEL_METRICS_ENABLED", () -> "true");
        registry.add("LH_OTEL_METRICS_URL", () -> "http://127.0.0.1:" + OTLP.getAddress().getPort() + "/v1/metrics");
        registry.add("LH_OTEL_METRICS_STEP", () -> "1s");
    }

    @AfterAll
    void tearDown() throws Exception {
        OTLP.stop(0);
        PG.close();
    }

    @Test
    void httpAndLhMetricsAreExportedInOtlpProtobufWithResourceAttributes() throws InterruptedException {
        // Una richiesta HTTP (http.server.requests) e una metrica custom registrata via LhMetrics (lh_*).
        String health = RestClient.create("http://localhost:" + port).get().uri("/actuator/health")
                .retrieve().body(String.class);
        assertThat(health).contains("UP");
        context.getBean(LhMetrics.class).eventConsumed("io.loyaltyhub.test.otlp");

        // lh_action_to_points_seconds è lo SLI azione → punti di insight (ActionToPointsSli): registrato all'avvio sul
        // MeterRegistry iniettato, arriva in OTLP anche senza traffico (il registro esporta a ogni passo i contatori a zero).
        List<String> expected = List.of("http.server.requests", "lh_events_consumed_total", "lh_action_to_points_seconds",
                "service.namespace", "loyaltyhub");
        long deadline = System.currentTimeMillis() + 20_000;
        boolean found = false;
        while (System.currentTimeMillis() < deadline && !found) {
            found = RECEIVED.stream().anyMatch(r -> containsAll(r.body(), expected));
            if (!found) {
                Thread.sleep(250);
            }
        }

        assertThat(RECEIVED).as("il collector finto ha ricevuto almeno un invio OTLP").isNotEmpty();
        assertThat(found)
                .as("un invio contiene %s (ricevuti %d invii)", expected, RECEIVED.size())
                .isTrue();
        assertThat(RECEIVED).allSatisfy(r -> assertThat(r.contentType()).contains("application/x-protobuf"));
    }

    // ---------- helper ----------

    private static boolean containsAll(byte[] body, List<String> needles) {
        return needles.stream().allMatch(n -> indexOf(body, n.getBytes(StandardCharsets.UTF_8)) >= 0);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** Ricevitore OTLP finto: risponde 200 e conserva corpo e Content-Type di ogni POST su /v1/metrics. */
    private static HttpServer startOtlpStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/v1/metrics", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                RECEIVED.add(new Received(String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")), body));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
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
