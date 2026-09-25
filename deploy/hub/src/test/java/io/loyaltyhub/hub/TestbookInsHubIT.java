package io.loyaltyhub.hub;

import io.loyaltyhub.ingestion.domain.ScenarioTime;
import io.loyaltyhub.insight.live.LiveEventHub;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-INS — righe tra servizi (docs/testbook/TB-INS-insight.md §13): nel deployable consolidato (profilo
 * {@code inproc}, bus in-process) insight osserva il traffico reale prodotto dalle API pubbliche. Tracciato e flusso
 * live di un acquisto (insight §7), DLQ di {@code SCN-POISON}, audit di ogni famiglia di scrittura da backoffice
 * (F-AUD-01: CREATE, UPDATE, TRANSITION con override, ADJUST, DELETE, JOB, RESET; nessuna voce per una scrittura
 * rifiutata), statistica del topic azioni con {@code SCN-WEEKEND-BURST}. Oracolo: insight §3, §5, §7; docs/05 §6;
 * docs/06 §3, §10; docs/08 §2, BO-22, BO-24, BO-25, BO-27; docs/12 M2.
 */
@SpringBootTest(
        classes = HubApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=hub", "loyaltyhub.insight.retention.cron=-"})
@ActiveProfiles({"demo", "inproc"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TestbookInsHubIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String ADMIN = "ADMIN:marta.admin";
    private static final String MARKETING = "MARKETING:luca.marketing";
    private static final String CARE = "CARE:carla.care";

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private LiveEventHub liveHub;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url",
                () -> base + "&currentSchema=ingestion,member,campaign,wallet,insight,reward,gamification,engagement");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    // ---------- attrezzi ----------

    record Resp(int status, JsonNode body, String text) {
    }

    private Resp call(String method, String path, String actor, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(40));
            if (actor != null) {
                b.header("X-LH-Actor", actor);
            }
            if (body != null) {
                b.header("Content-Type", "application/json");
                b.method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode node;
            try {
                node = r.body() == null || r.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(r.body());
            } catch (RuntimeException e) {
                node = mapper.createObjectNode();
            }
            return new Resp(r.statusCode(), node, r.body());
        } catch (Exception e) {
            throw new IllegalStateException(method + " " + path, e);
        }
    }

    private JsonNode ok(String method, String path, String actor, Object body) {
        Resp r = call(method, path, actor, body);
        assertThat(r.status()).as(method + " " + path + " → " + r.text()).isBetween(200, 299);
        return r.body();
    }

    private JsonNode get(String path) {
        return ok("GET", path, null, null);
    }

    private static <T> T await(String what, Supplier<T> s, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            T v = s.get();
            if (v != null) {
                return v;
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        T v = s.get();
        if (v == null) {
            throw new AssertionError("Attesa scaduta: " + what);
        }
        return v;
    }

    private static String uid(String prefix) {
        return prefix + "-" + Long.toString(System.nanoTime(), 36).toUpperCase() + SEQ.incrementAndGet();
    }

    private JsonNode auditOf(String entityId, String action) {
        return await("audit " + action + " di " + entityId, () -> {
            for (JsonNode i : get("/v1/audit?entityId=" + entityId).path("items")) {
                if (action.equals(i.path("action").asString())) {
                    return i;
                }
            }
            return null;
        }, 20_000);
    }

    private String purchase(String memberId, String orderId) {
        String weekday = ScenarioTime.resolve("@lastWeekdayT10:00", Instant.now()).toString();
        JsonNode r = ok("POST", "/v1/events", null, Map.of(
                "specversion", "1.0", "id", uid("tb-ins-hub"), "source", "urn:loyaltyhub:source:ecommerce",
                "type", "purchase.completed", "subject", "member:" + memberId, "time", weekday,
                "data", Map.of("orderId", orderId, "amount", 130, "currency", "EUR", "channel", "ONLINE")));
        assertThat(r.path("status").asString()).isEqualTo("ACCEPTED");
        return r.path("correlationId").asString();
    }

    /** Tracciato; nodo vuoto finché è 404 (nessun evento ancora arrivato, Q-N7 DECISA). */
    private JsonNode trace(String cor) {
        Resp r = call("GET", "/v1/traces/" + cor, null, null);
        if (r.status() == 404) {
            return mapper.createObjectNode();
        }
        assertThat(r.status()).as("GET /v1/traces/" + cor + " → " + r.text()).isEqualTo(200);
        return r.body();
    }

    private Map<String, Long> points(JsonNode trace) {
        Map<String, Long> m = new LinkedHashMap<>();
        trace.path("outcome").path("points").forEach(p -> m.put(p.path("currency").asString(), p.path("amount").asLong()));
        return m;
    }

    private Map<String, Object> campaignBody(String code, String name, boolean requiresLegal) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("code", code);
        b.put("name", name);
        b.put("memberDescription", "Campagna del testbook");
        b.put("priority", 100);
        b.put("visibleInPortal", false);
        b.put("requiresLegal", requiresLegal);
        b.put("triggerActionTypes", List.of("survey.completed"));
        b.put("audience", Map.of("all", true));
        b.put("conditions", Map.of("op", "all", "rules", List.of()));
        b.put("effects", List.of(Map.of("type", "GRANT_POINTS", "currency", "PTS", "mode", "FIXED", "value", 5,
                "tierMultiplierApplies", false)));
        b.put("limits", Map.of());
        b.put("schedule", Map.of("startAt", "2030-01-01T00:00:00Z"));
        return b;
    }

    // ---------- tracciato e flusso live ----------

    @Test
    @Order(1)
    @DisplayName("[TB-INS-HUB-001] acquisto SILVER 130 € (insight §7): una radice = l'azione, durationMs > 0, esito [{PTS,162},{STS,130}]")
    void purchaseTrace() {
        String cor = purchase("MBR-000002", uid("ORD-TB"));
        JsonNode tr = await("tracciato completo", () -> {
            JsonNode t = trace(cor);
            return points(t).containsKey("STS") && points(t).containsKey("PTS") ? t : null;
        }, 20_000);
        List<String> roots = new ArrayList<>();
        Set<String> types = new HashSet<>();
        for (JsonNode n : tr.path("nodes")) {
            types.add(n.path("shortType").asString());
            if (n.path("parentEventId").isMissingNode() || n.path("parentEventId").isNull()) {
                roots.add(n.path("shortType").asString());
            }
        }
        assertThat(roots).containsExactly("purchase.completed");
        assertThat(types).contains("campaign.evaluated", "points.grant", "wallet.points.earned");
        assertThat(tr.path("durationMs").asLong()).isPositive();
        assertThat(points(tr)).containsExactly(Map.entry("PTS", 162L), Map.entry("STS", 130L));
    }

    @Test
    @Order(2)
    @DisplayName("[TB-INS-HUB-002] dopo 5 s senza nuovi eventi il tracciato dell'acquisto è COMPLETE")
    void purchaseComplete() {
        String cor = purchase("MBR-000002", uid("ORD-TB"));
        assertThat(await("COMPLETE", () -> "COMPLETE".equals(trace(cor).path("status").asString()) ? "ok" : null, 30_000))
                .isEqualTo("ok");
    }

    @Test
    @Order(3)
    @DisplayName("[TB-INS-HUB-003] flusso live di un acquisto (insight §7): entro 3 s ≥ 4 messaggi SSE con lo stesso correlationId (azione, valutazione, effetto punti, accredito)")
    void purchaseLive() throws Exception {
        BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
        int before = liveHub.subscriberCount();
        CompletableFuture<HttpResponse<InputStream>> f = HTTP.sendAsync(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/v1/stream/events?memberId=MBR-000004"))
                .header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        f.thenAccept(r -> {
            Thread t = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.startsWith("data:")) {
                            events.add(mapper.readTree(line.substring(5)));
                        }
                    }
                } catch (Exception ignored) {
                    // chiuso
                }
            });
            t.setDaemon(true);
            t.start();
        });
        await("iscrizione SSE", () -> liveHub.subscriberCount() > before ? "ok" : null, 10_000);
        try {
            long start = System.currentTimeMillis();
            String cor = purchase("MBR-000004", uid("ORD-TB"));
            Set<String> types = new HashSet<>();
            while (System.currentTimeMillis() - start < 3_000) {
                JsonNode e = events.poll(100, TimeUnit.MILLISECONDS);
                if (e != null && cor.equals(e.path("correlationId").asString())) {
                    types.add(e.path("shortType").asString());
                }
            }
            assertThat(types).contains("purchase.completed", "campaign.evaluated", "points.grant", "wallet.points.earned");
        } finally {
            f.cancel(true);
            if (f.isDone() && !f.isCancelled()) {
                f.get().body().close();
            }
        }
    }

    // ---------- DLQ ----------

    private String poisonEntry;
    private String poisonCorrelation;

    @Test
    @Order(4)
    @DisplayName("[TB-INS-HUB-004] SCN-POISON (insight §7): voce DLQ DEMO_POISON del consumer lh-campaign, azione riprocessabile, tracciato FAILED")
    void poison() {
        JsonNode started = ok("POST", "/v1/demo/scenarios/SCN-POISON/run", ADMIN, null);
        String runId = started.path("runId").asString();
        JsonNode run = await("scenario", () -> {
            JsonNode r = get("/v1/demo/scenario-runs/" + runId);
            return "RUNNING".equals(r.path("status").asString()) ? null : r;
        }, 20_000);
        String eventId = run.path("results").get(0).path("eventId").asString();
        poisonCorrelation = run.path("results").get(0).path("correlationId").asString();
        JsonNode entry = await("voce DLQ", () -> {
            for (JsonNode i : get("/v1/dlq?size=100&consumer=lh-campaign").path("items")) {
                if (eventId.equals(i.path("eventId").asString())) {
                    return i;
                }
            }
            return null;
        }, 30_000);
        poisonEntry = entry.path("id").asString();
        assertThat(entry.path("errorCode").asString()).isEqualTo("DEMO_POISON");
        assertThat(entry.path("family").asString()).isEqualTo("ACTION");
        assertThat(entry.path("reprocessable").asBoolean()).isTrue();
        assertThat(await("FAILED", () -> "FAILED".equals(trace(poisonCorrelation).path("status").asString()) ? "ok" : null,
                20_000)).isEqualTo("ok");
    }

    @Test
    @Order(5)
    @DisplayName("[TB-INS-HUB-005] scarta della voce avvelenata: DISCARDED, audit TRANSITION dell'ADMIN, tracciato ancora FAILED (Q-106)")
    void poisonDiscard() {
        assertThat(poisonEntry).as("dipende da HUB-004").isNotNull();
        JsonNode r = ok("POST", "/v1/dlq/" + poisonEntry + "/discard", ADMIN, Map.of("note", "Messaggio di prova avvelenato"));
        assertThat(r.path("status").asString()).isEqualTo("DISCARDED");
        JsonNode a = auditOf(poisonEntry, "TRANSITION");
        assertThat(a.path("actorName").asString()).isEqualTo("marta.admin");
        assertThat(trace(poisonCorrelation).path("status").asString()).isEqualTo("FAILED");
        assertThat(a.path("service").asString()).isEqualTo("insight");
    }

    // ---------- audit di ogni famiglia di scrittura ----------

    private String campaignCode;

    @Test
    @Order(10)
    @DisplayName("[TB-INS-HUB-006] CREATE: nuova campagna da MARKETING ⇒ voce di audit con servizio, oggetto, attore e istante")
    void auditCreate() {
        campaignCode = "CMP-TBINS-" + SEQ.incrementAndGet() + Long.toString(System.nanoTime() % 100000, 36).toUpperCase();
        Instant t0 = Instant.now().minusSeconds(1);
        ok("POST", "/v1/campaigns", MARKETING, campaignBody(campaignCode, "Campagna testbook", false));
        JsonNode a = auditOf(campaignCode, "CREATE");
        assertThat(a.path("entityType").asString()).isEqualTo("CAMPAIGN");
        assertThat(a.path("actorRole").asString()).isEqualTo("MARKETING");
        assertThat(a.path("actorName").asString()).isEqualTo("luca.marketing");
        Instant at = Instant.parse(a.path("at").asString());
        assertThat(at).isBetween(t0, Instant.now().plusSeconds(1));
        assertThat(a.path("service").asString()).isEqualTo("campaign");
    }

    @Test
    @Order(11)
    @DisplayName("[TB-INS-HUB-007] UPDATE: nome cambiato ⇒ diff con il solo campo cambiato (prima/dopo)")
    void auditUpdate() {
        assertThat(campaignCode).as("dipende da HUB-006").isNotNull();
        ok("PUT", "/v1/campaigns/" + campaignCode, MARKETING, campaignBody(campaignCode, "Campagna testbook rinominata", false));
        JsonNode a = auditOf(campaignCode, "UPDATE");
        assertThat(a.path("before").path("name").asString()).isEqualTo("Campagna testbook");
        assertThat(a.path("after").path("name").asString()).isEqualTo("Campagna testbook rinominata");
        assertThat(a.path("after").has("priority")).as("solo i campi cambiati (docs/05 §6)").isFalse();
    }

    @Test
    @Order(12)
    @DisplayName("[TB-INS-HUB-008] TRANSITION: SUBMIT della campagna ⇒ voce TRANSITION dell'attore")
    void auditTransition() {
        assertThat(campaignCode).as("dipende da HUB-006").isNotNull();
        ok("POST", "/v1/campaigns/" + campaignCode + "/transitions", MARKETING, Map.of("action", "SUBMIT"));
        JsonNode a = auditOf(campaignCode, "TRANSITION");
        assertThat(a.path("actorRole").asString()).isEqualTo("MARKETING");
    }

    @Test
    @Order(13)
    @DisplayName("[TB-INS-HUB-009] override: ADMIN approva al posto di LEGAL ⇒ audit TRANSITION marcato «override» (docs/08 §2, BO-22)")
    void auditOverride() {
        String code = "CMP-TBOVR-" + SEQ.incrementAndGet() + Long.toString(System.nanoTime() % 100000, 36).toUpperCase();
        ok("POST", "/v1/campaigns", MARKETING, campaignBody(code, "Campagna con LEGAL", true));
        ok("POST", "/v1/campaigns/" + code + "/transitions", MARKETING, Map.of("action", "SUBMIT"));
        ok("POST", "/v1/campaigns/" + code + "/transitions", ADMIN, Map.of("action", "APPROVE", "comment", "ok"));
        JsonNode a = await("audit APPROVE", () -> {
            for (JsonNode i : get("/v1/audit?entityId=" + code + "&action=TRANSITION").path("items")) {
                if ("ADMIN".equals(i.path("actorRole").asString())) {
                    return i;
                }
            }
            return null;
        }, 20_000);
        assertThat(a.path("summary").asString()).containsIgnoringCase("override");
    }

    @Test
    @Order(14)
    @DisplayName("[TB-INS-HUB-010] ADJUST: rettifica manuale da CARE ⇒ voce ADJUST del wallet con saldo prima/dopo")
    void auditAdjust() {
        ok("POST", "/v1/wallets/MBR-000005/adjustments", CARE, Map.of("currency", "PTS", "direction", "CREDIT",
                "amount", 100, "reason", "GOODWILL", "note", "Rettifica del testbook"));
        JsonNode a = auditOf("MBR-000005:PTS", "ADJUST");
        assertThat(a.path("actorRole").asString()).isEqualTo("CARE");
        assertThat(a.path("after").path("balance").asLong() - a.path("before").path("balance").asLong()).isEqualTo(100);
        assertThat(a.path("service").asString()).isEqualTo("wallet");
    }

    @Test
    @Order(15)
    @DisplayName("[TB-INS-HUB-011] UPDATE del membro da CARE ⇒ voce del servizio member con attore CARE")
    void auditMember() {
        long version = get("/v1/members/MBR-000006").path("version").asLong();
        ok("PATCH", "/v1/members/MBR-000006", CARE, Map.of("version", version, "city", "Ancona"));
        JsonNode a = auditOf("MBR-000006", "UPDATE");
        assertThat(a.path("actorName").asString()).isEqualTo("carla.care");
        assertThat(a.path("service").asString()).isEqualTo("member");
    }

    @Test
    @Order(16)
    @DisplayName("[TB-INS-HUB-012] DELETE: eliminazione di un webhook da ADMIN ⇒ voce DELETE di engagement")
    void auditDelete() {
        String code = "WH-TBINS-" + SEQ.incrementAndGet();
        ok("POST", "/v1/webhooks", ADMIN, Map.of("code", code, "name", "Webhook testbook",
                "url", "https://testbook.invalid/hook", "factTypes", List.of("tier.upgraded")));
        ok("DELETE", "/v1/webhooks/" + code, ADMIN, null);
        JsonNode a = auditOf(code, "DELETE");
        assertThat(a.path("actorRole").asString()).isEqualTo("ADMIN");
        assertThat(a.path("service").asString()).isEqualTo("engagement");
    }

    @Test
    @Order(17)
    @DisplayName("[TB-INS-HUB-013] JOB: job scadenze ⇒ voce JOB con attore system (docs/05 §2 lhactor dei job)")
    void auditJob() {
        String asOf = LocalDate.now(ZoneId.of("Europe/Rome")).plusDays(1).toString();
        ok("POST", "/v1/demo/jobs/expire-points?asOf=" + asOf, ADMIN, null);
        JsonNode a = auditOf("expire-points", "JOB");
        assertThat(a.path("actorRole").asString()).isEqualTo("system");
        assertThat(a.path("service").asString()).isEqualTo("wallet");
    }

    @Test
    @Order(18)
    @DisplayName("[TB-INS-HUB-014] scrittura rifiutata (ANALYST crea una campagna: 403) ⇒ nessuna voce di audit")
    void noAuditOnForbidden() {
        String refused = "CMP-TBNO-" + SEQ.incrementAndGet();
        assertThat(call("POST", "/v1/campaigns", "ANALYST:andrea.analyst", campaignBody(refused, "No", false)).status())
                .isEqualTo(403);
        String sentinel = "CMP-TBSEN-" + SEQ.incrementAndGet();
        ok("POST", "/v1/campaigns", MARKETING, campaignBody(sentinel, "Sentinella", false));
        auditOf(sentinel, "CREATE");
        assertThat(get("/v1/audit?entityId=" + refused).path("items").size()).isZero();
    }

    // ---------- pipeline ----------

    private long actionsCount() {
        for (JsonNode t : get("/v1/pipeline/status").path("topics")) {
            if ("lh.actions.v1".equals(t.path("topic").asString())) {
                return t.path("countTotal").asLong();
            }
        }
        return 0;
    }

    @Test
    @Order(20)
    @DisplayName("[TB-INS-HUB-015] SCN-WEEKEND-BURST (docs/12 M2): le 12 azioni in event store su lh.actions.v1 e il topic conta almeno +12")
    void burst() {
        long before = actionsCount();
        JsonNode started = ok("POST", "/v1/demo/scenarios/SCN-WEEKEND-BURST/run", ADMIN, null);
        String runId = started.path("runId").asString();
        JsonNode run = await("scenario", () -> {
            JsonNode r = get("/v1/demo/scenario-runs/" + runId);
            return "RUNNING".equals(r.path("status").asString()) ? null : r;
        }, 90_000);
        List<String> ids = new ArrayList<>();
        run.path("results").forEach(r -> ids.add(r.path("eventId").asString()));
        assertThat(ids).hasSize(12);
        for (String id : ids) {
            JsonNode e = await("azione " + id, () -> {
                Resp r = call("GET", "/v1/events/" + id, null, null);
                return r.status() == 200 ? r.body() : null;
            }, 20_000);
            assertThat(e.path("topic").asString()).isEqualTo("lh.actions.v1");
        }
        // Le 12 azioni esterne; il ponte interno (docs/05 §7) può aggiungere azioni sullo stesso topic.
        assertThat(actionsCount() - before).isGreaterThanOrEqualTo(12);
    }

    // ---------- reset (ultimo: svuota insight) ----------

    @Test
    @Order(99)
    @DisplayName("[TB-INS-HUB-016] RESET: reset demo da ADMIN ⇒ voce di audit RESET con l'attore (docs/06 §10)")
    void auditReset() {
        ok("POST", "/v1/demo/reset", ADMIN, null);
        JsonNode a = await("audit RESET", () -> {
            JsonNode items = get("/v1/audit?action=RESET").path("items");
            return items.size() > 0 ? items.get(0) : null;
        }, 15_000);
        assertThat(a.path("actorRole").asString()).isEqualTo("ADMIN");
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
