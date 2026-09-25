package io.loyaltyhub.insight;

import io.loyaltyhub.insight.live.LiveEvent;
import io.loyaltyhub.insight.live.LiveEventHub;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-INS — flusso live SSE (docs/testbook/TB-INS-insight.md §6): ripresa con {@code Last-Event-ID}
 * ({@code SRP}: finestra di 200, id sconosciuto, buchi) e comportamento del canale ({@code SSE}: formato, filtri,
 * heartbeat, client chiuso o lento, CORS). Contesto proprio: nessun altro caso pubblica sul bus, così il ring buffer
 * contiene solo gli eventi del caso. Gli eventi del buffer sono pubblicati sul {@link LiveEventHub}; il percorso
 * Kafka → ingest → SSE è provato da SSE-001 (e da DIG-017, ING-008). Oracolo: insight §3, §5, §7; ADR-020; Q-26.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "loyaltyhub.insight.retention.cron=-")
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TestbookInsStreamIT extends TestbookInsSupport {

    static final EmbeddedPostgres PG_STREAM = startPg();

    @Autowired
    private LiveEventHub hub;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG_STREAM.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=insight");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    private List<String> publishLive(String cor, int n) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String id = uid("LIVE");
            hub.publish(new LiveEvent(id, "lh.actions.v1", "ACTION", "app.login.daily", "MBR-000002", cor,
                    Instant.now(), "Accesso all'app"));
            ids.add(id);
        }
        return ids;
    }

    private static List<String> ids(List<SseEvent> events) {
        return events.stream().map(SseEvent::id).toList();
    }

    // ---------- SRP: ripresa con Last-Event-ID ----------

    @Test
    @Order(1)
    @DisplayName("[TB-INS-SRP-001] senza Last-Event-ID: nessun rinvio degli eventi passati, arrivano solo i nuovi")
    void noHeader() throws Exception {
        String cor = uid("COR-SRP");
        publishLive(cor, 3);
        try (SseClient c = new SseClient("?correlationId=" + cor, null, Map.of())) {
            List<String> fresh = publishLive(cor, 1);
            List<SseEvent> got = c.take(1, 5_000);
            got.addAll(c.drain(500));
            assertThat(ids(got)).containsExactlyElementsOf(fresh);
        }
    }

    @Test
    @Order(2)
    @DisplayName("[TB-INS-SRP-002] Last-Event-ID a metà: rinviati in ordine gli eventi successivi")
    void middle() throws Exception {
        String cor = uid("COR-SRP");
        List<String> all = publishLive(cor, 5);
        try (SseClient c = new SseClient("?correlationId=" + cor, all.get(1), Map.of())) {
            List<SseEvent> got = c.take(3, 5_000);
            got.addAll(c.drain(500));
            assertThat(ids(got)).containsExactlyElementsOf(all.subList(2, 5));
        }
    }

    @Test
    @Order(3)
    @DisplayName("[TB-INS-SRP-003] Last-Event-ID = ultimo evento: nulla da rinviare")
    void latest() throws Exception {
        String cor = uid("COR-SRP");
        List<String> all = publishLive(cor, 4);
        try (SseClient c = new SseClient("?correlationId=" + cor, all.get(3), Map.of())) {
            assertThat(c.drain(800)).isEmpty();
        }
    }

    @Test
    @Order(4)
    @DisplayName("[TB-INS-SRP-004] Last-Event-ID sconosciuto (AMBIGUO): rinviato tutto il buffer che passa il filtro")
    void unknownId() throws Exception {
        // TESTBOOK: ambiguo, vedi TB-INS-SRP-004
        String cor = uid("COR-SRP");
        List<String> all = publishLive(cor, 5);
        try (SseClient c = new SseClient("?correlationId=" + cor, "ID-MAI-VISTO", Map.of())) {
            List<SseEvent> got = c.take(5, 5_000);
            got.addAll(c.drain(500));
            assertThat(ids(got)).containsExactlyElementsOf(all);
        }
    }

    @Test
    @Order(5)
    @DisplayName("[TB-INS-SRP-005] Last-Event-ID di soli spazi: come assente, nessun rinvio")
    void blankId() throws Exception {
        String cor = uid("COR-SRP");
        publishLive(cor, 3);
        try (SseClient c = new SseClient("?correlationId=" + cor, "   ", Map.of())) {
            assertThat(c.drain(800)).isEmpty();
        }
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @Order(6)
    @CsvSource({
            "TB-INS-SRP-006,1 evento perso: rinviato,1,1",
            "TB-INS-SRP-007,199 eventi persi: rinviati tutti,199,199",
            "TB-INS-SRP-008,200 eventi persi (limite): rinviati tutti,200,200",
            "TB-INS-SRP-009,201 eventi persi: rinviati gli ultimi 200 (buco di 1),201,200",
            "TB-INS-SRP-010,250 eventi persi: rinviati gli ultimi 200 (buco di 50),250,200"})
    void window(String id, String desc, int missed, int expected) throws Exception {
        String cor = uid("COR-SRP");
        String marker = publishLive(cor, 1).get(0);
        List<String> lost = publishLive(cor, missed);
        try (SseClient c = new SseClient("?correlationId=" + cor, marker, Map.of())) {
            List<SseEvent> got = c.take(expected, 10_000);
            got.addAll(c.drain(500));
            assertThat(ids(got)).containsExactlyElementsOf(lost.subList(missed - expected, missed));
        }
    }

    @Test
    @Order(7)
    @DisplayName("[TB-INS-SRP-011] il rinvio rispetta il filtro: solo gli eventi del correlationId richiesto")
    void replayFiltered() throws Exception {
        String a = uid("COR-A");
        String b = uid("COR-B");
        String marker = publishLive(a, 1).get(0);
        List<String> wanted = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            wanted.addAll(publishLive(a, 1));
            publishLive(b, 1);
        }
        try (SseClient c = new SseClient("?correlationId=" + a, marker, Map.of())) {
            List<SseEvent> got = c.take(3, 5_000);
            got.addAll(c.drain(500));
            assertThat(ids(got)).containsExactlyElementsOf(wanted);
        }
    }

    @Test
    @Order(8)
    @DisplayName("[TB-INS-SRP-012] dopo il rinvio il flusso continua dal vivo, senza duplicati")
    void replayThenLive() throws Exception {
        String cor = uid("COR-SRP");
        List<String> all = publishLive(cor, 3);
        try (SseClient c = new SseClient("?correlationId=" + cor, all.get(0), Map.of())) {
            List<SseEvent> got = c.take(2, 5_000);
            List<String> fresh = publishLive(cor, 2);
            got.addAll(c.take(2, 5_000));
            got.addAll(c.drain(500));
            List<String> expected = new ArrayList<>(all.subList(1, 3));
            expected.addAll(fresh);
            assertThat(ids(got)).containsExactlyElementsOf(expected);
        }
    }

    @Test
    @Order(9)
    @DisplayName("[TB-INS-SRP-013] riconnessione con Last-Event-ID (insight §7, 20 eventi): nessun evento perso né doppio")
    void reconnect20() throws Exception {
        String cor = uid("COR-SRP");
        Set<String> seen = new LinkedHashSet<>();
        List<String> sent = new ArrayList<>();
        String last;
        try (SseClient c = new SseClient("?correlationId=" + cor, null, Map.of())) {
            sent.addAll(publishLive(cor, 10));
            List<SseEvent> first = c.take(10, 5_000);
            first.forEach(e -> assertThat(seen.add(e.id())).as("duplicato " + e.id()).isTrue());
            last = first.get(first.size() - 1).id();
        }
        sent.addAll(publishLive(cor, 10));
        try (SseClient c = new SseClient("?correlationId=" + cor, last, Map.of())) {
            List<SseEvent> second = c.take(10, 5_000);
            second.addAll(c.drain(500));
            second.forEach(e -> assertThat(seen.add(e.id())).as("duplicato " + e.id()).isTrue());
        }
        assertThat(seen).containsExactlyElementsOf(sent);
    }

    // ---------- SSE: canale ----------

    @Test
    @Order(20)
    @DisplayName("[TB-INS-SSE-001] acquisto dal topic: evento lh-event con id = eventId e data {eventId, topic, family, shortType, memberId, correlationId, time, summary}")
    void format() throws Exception {
        String cor = uid("COR-SSE");
        String ev = uid("EVT-SSE");
        try (SseClient c = new SseClient("?correlationId=" + cor, null, Map.of())) {
            assertThat(c.response().statusCode()).isEqualTo(200);
            assertThat(c.response().headers().firstValue("content-type").orElse("")).contains("text/event-stream");
            publish("lh.actions.v1", envelope(ev, ACTION + "purchase.completed", "urn:loyaltyhub:source:ecommerce",
                    "member:MBR-000003", cor, null, Instant.parse("2026-09-18T10:15:00Z"),
                    Map.of("orderId", "ORD-1", "amount", 130, "currency", "EUR")));
            List<SseEvent> got = c.take(1, 10_000);
            assertThat(got).hasSize(1);
            assertThat(got.get(0).event()).isEqualTo("lh-event");
            assertThat(got.get(0).id()).isEqualTo(ev);
            JsonNode d = mapper.readTree(got.get(0).data());
            List<String> fields = new ArrayList<>();
            d.propertyNames().forEach(fields::add);
            assertThat(fields).containsExactlyInAnyOrder("eventId", "topic", "family", "shortType", "memberId",
                    "correlationId", "time", "summary");
            assertThat(d.path("topic").asString()).isEqualTo("lh.actions.v1");
            assertThat(d.path("family").asString()).isEqualTo("ACTION");
            assertThat(d.path("shortType").asString()).isEqualTo("purchase.completed");
            assertThat(d.path("memberId").asString()).isEqualTo("MBR-000003");
            assertThat(d.path("time").asString()).isEqualTo("2026-09-18T10:15:00Z");
            assertThat(d.path("summary").asString()).contains("130");
        }
    }

    @Test
    @Order(21)
    @DisplayName("[TB-INS-SSE-002] filtro topics con due valori separati da virgola e spazi: arrivano azioni e fatti, non gli effetti")
    void topicsFilter() throws Exception {
        String cor = uid("COR-SSE");
        try (SseClient c = new SseClient("?topics=lh.actions.v1,%20lh.facts.v1&correlationId=" + cor, null, Map.of())) {
            hub.publish(new LiveEvent("A-" + cor, "lh.actions.v1", "ACTION", "x", null, cor, Instant.now(), "a"));
            hub.publish(new LiveEvent("E-" + cor, "lh.effects.v1", "EFFECT", "x", null, cor, Instant.now(), "e"));
            hub.publish(new LiveEvent("F-" + cor, "lh.facts.v1", "FACT", "x", null, cor, Instant.now(), "f"));
            List<SseEvent> got = c.take(2, 5_000);
            got.addAll(c.drain(500));
            assertThat(ids(got)).containsExactly("A-" + cor, "F-" + cor);
        }
    }

    @Test
    @Order(22)
    @DisplayName("[TB-INS-SSE-003] filtro memberId: solo gli eventi del membro")
    void memberFilter() throws Exception {
        String cor = uid("COR-SSE");
        try (SseClient c = new SseClient("?memberId=MBR-000007&correlationId=" + cor, null, Map.of())) {
            hub.publish(new LiveEvent("M7-" + cor, "lh.facts.v1", "FACT", "x", "MBR-000007", cor, Instant.now(), "a"));
            hub.publish(new LiveEvent("M8-" + cor, "lh.facts.v1", "FACT", "x", "MBR-000008", cor, Instant.now(), "b"));
            List<SseEvent> got = c.take(1, 5_000);
            got.addAll(c.drain(500));
            assertThat(ids(got)).containsExactly("M7-" + cor);
        }
    }

    @Test
    @Order(23)
    @DisplayName("[TB-INS-SSE-004] filtro types sul tipo breve: solo wallet.points.earned")
    void typesFilter() throws Exception {
        String cor = uid("COR-SSE");
        try (SseClient c = new SseClient("?types=wallet.points.earned&correlationId=" + cor, null, Map.of())) {
            hub.publish(new LiveEvent("W-" + cor, "lh.facts.v1", "FACT", "wallet.points.earned", null, cor, Instant.now(), "a"));
            hub.publish(new LiveEvent("T-" + cor, "lh.facts.v1", "FACT", "tier.upgraded", null, cor, Instant.now(), "b"));
            List<SseEvent> got = c.take(1, 5_000);
            got.addAll(c.drain(500));
            assertThat(ids(got)).containsExactly("W-" + cor);
        }
    }

    @Test
    @Order(24)
    @DisplayName("[TB-INS-SSE-005] due client con filtri diversi: ognuno riceve solo i suoi eventi")
    void twoClients() throws Exception {
        String a = uid("COR-A");
        String b = uid("COR-B");
        try (SseClient ca = new SseClient("?correlationId=" + a, null, Map.of());
             SseClient cb = new SseClient("?correlationId=" + b, null, Map.of())) {
            List<String> ea = publishLive(a, 2);
            List<String> eb = publishLive(b, 3);
            List<SseEvent> ga = ca.take(2, 5_000);
            ga.addAll(ca.drain(300));
            List<SseEvent> gb = cb.take(3, 5_000);
            gb.addAll(cb.drain(300));
            assertThat(ids(ga)).containsExactlyElementsOf(ea);
            assertThat(ids(gb)).containsExactlyElementsOf(eb);
        }
    }

    @Test
    @Order(25)
    @DisplayName("[TB-INS-SSE-006] heartbeat ogni 15 s (AMBIGUO sulla forma: commento SSE): arriva entro 16 s su un canale muto")
    void heartbeat() throws Exception {
        // TESTBOOK: ambiguo, vedi TB-INS-SSE-006 (insight §3 «heartbeat»: evento con nome o commento)
        try (SseClient c = new SseClient("?correlationId=" + uid("COR-MUTO"), null, Map.of())) {
            await("heartbeat", () -> !c.comments.isEmpty()
                    || c.events.stream().anyMatch(e -> "heartbeat".equals(e.event())), 16_500);
        }
    }

    @Test
    @Order(26)
    @DisplayName("[TB-INS-SSE-007] client che chiude: rimosso dagli iscritti senza errori per gli altri")
    void closedClientRemoved() throws Exception {
        String cor = uid("COR-SSE");
        SseClient c = new SseClient("?correlationId=" + cor, null, Map.of());
        publishLive(cor, 1);
        assertThat(c.take(1, 5_000)).hasSize(1);
        int withClient = hub.subscriberCount();
        c.close();
        await("rimosso", () -> {
            publishLive(cor, 1);
            return hub.subscriberCount() < withClient;
        }, 15_000);
        try (SseClient other = new SseClient("?correlationId=" + cor, null, Map.of())) {
            List<String> fresh = publishLive(cor, 1);
            assertThat(ids(other.take(1, 5_000))).containsExactlyElementsOf(fresh);
        }
    }

    @Test
    @Order(27)
    @DisplayName("[TB-INS-SSE-008] CORS per l'EventSource del browser (ADR-020): Access-Control-Allow-Origin sulla risposta")
    void cors() throws Exception {
        String cor = uid("COR");
        try (SseClient c = new SseClient("?correlationId=" + cor, null, Map.of("Origin", "https://demo.example.org"))) {
            publishLive(cor, 1);
            assertThat(c.response().headers().firstValue("Access-Control-Allow-Origin").orElse(""))
                    .isIn("*", "https://demo.example.org");
        }
    }

    @Test
    @Order(99)
    @DisplayName("[TB-INS-SSE-009] client lento che non legge (coda oltre 500): disconnesso, gli altri client continuano a ricevere")
    void slowClient() throws Exception {
        String cor = uid("COR-LENTO");
        Socket slow = new Socket();
        slow.setReceiveBufferSize(1024);
        slow.connect(new InetSocketAddress("localhost", port));
        OutputStream out = slow.getOutputStream();
        out.write(("GET /v1/stream/events?correlationId=" + cor + " HTTP/1.1\r\nHost: localhost\r\n"
                + "Accept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        String healthyCor = uid("COR-SANO");
        try (SseClient healthy = new SseClient("?correlationId=" + healthyCor, null, Map.of())) {
            String payload = "x".repeat(4096);
            for (int i = 0; i < 4000; i++) {
                hub.publish(new LiveEvent(uid("SLOW"), "lh.facts.v1", "FACT", "x", null, cor, Instant.now(), payload));
            }

            List<String> fresh = new ArrayList<>();
            for (int i = 0; i < 1; i++) {
                String id = uid("SANO");
                hub.publish(new LiveEvent(id, "lh.facts.v1", "FACT", "x", null, healthyCor, Instant.now(), "ok"));
                fresh.add(id);
            }
            assertThat(ids(healthy.take(1, 10_000))).as("il client sano riceve ancora").containsExactlyElementsOf(fresh);
        } finally {
            slow.close();
        }
    }
}
