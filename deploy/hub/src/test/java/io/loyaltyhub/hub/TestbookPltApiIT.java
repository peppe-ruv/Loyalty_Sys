package io.loyaltyhub.hub;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-PLT nell'hub consolidato (ADR-023, profilo {@code demo,inproc} di ADR-024): errori RFC 9457 sulle API vere
 * (§HER), paginazione {@code {items, page}} di ogni elenco (§PAG), intestazione {@code X-LH-Actor} sull'endpoint ADMIN
 * del reset (§ACT), reset della demo idempotente e concorrente (§RST), {@code GET /v1/demo/info} (§INF), salute e
 * metriche in esecuzione (§HLR), requisiti non funzionali misurabili (§NFR) e limite di frequenza degli ingressi (§RLM).
 * Un solo contesto Spring; righe in {@code /testbook/plt/*.csv}; membri nuovi a ogni riga che scrive.
 */
@SpringBootTest(
        classes = {HubApplication.class, TestbookPltApiIT.Today.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=hub", "loyaltyhub.outbox.relay-interval-ms=50"})
@ActiveProfiles({"demo", "inproc"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TestbookPltApiIT extends TestbookPltSupportIT {

    private static final EmbeddedPostgres PG = startPg();

    /** Impronta del seed presa dopo il primo reset: oracolo di tutti gli altri reset. */
    private Map<String, java.util.List<String>> seedFingerprint;
    private Instant lastResetStarted;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        datasource(registry, PG);
    }

    @AfterAll
    void stop() throws IOException {
        PG.close();
    }

    static class Today {
        @Bean
        @Primary
        Clock testbookClock() {
            return startingAt("2026-09-24T10:00:00Z");
        }
    }

    // ================= GET /v1/demo/info prima di ogni reset =================

    @TestFactory
    @Order(1)
    Stream<DynamicTest> infoBeforeReset() {
        return plt("info-before.csv", row -> {
            JsonNode info = ok(send("GET", "/v1/demo/info", ADMIN, null), 200);
            assertThat(info.path("lastReset").isNull() || info.path("lastReset").isMissingNode())
                    .as("nessun reset dall'avvio: %s", info).isTrue();
        });
    }

    // ================= salute e metriche =================

    @TestFactory
    @Order(2)
    Stream<DynamicTest> health() {
        return plt("health.csv", row -> {
            String kase = row.get("caso");
            switch (kase) {
                case "health" -> {
                    JsonNode h = ok(send("GET", "/actuator/health", null, null), 200);
                    assertThat(h.path("status").asString() + " db=" + h.path("components").path("db").path("status").asString()
                            + " kafka=" + h.path("components").path("kafka").path("status").asString()
                            + " " + h.path("components").path("kafka").path("details").path("mode").asString())
                            .isEqualTo(row.get("atteso"));
                }
                case "liveness", "readiness" -> {
                    JsonNode h = ok(send("GET", "/actuator/health/" + kase, null, null), 200);
                    List<String> names = new ArrayList<>();
                    h.path("components").propertyNames().forEach(names::add);
                    names.sort(String::compareTo);
                    assertThat(h.path("status").asString() + " " + String.join(",", names)).isEqualTo(row.get("atteso"));
                }
                case "metric" -> {
                    purchase(newMember().subject(), 20, todayAt(9, 0));
                    quiet();
                    Resp r = send("GET", "/actuator/metrics/" + row.get("parametro"), null, null);
                    assertThat(String.valueOf(r.status())).as(r.body().toString()).isEqualTo(row.get("atteso"));
                }
                case "openapi" -> {
                    Raw r = raw("GET", "/v3/api-docs", headers(), null);
                    assertThat(String.valueOf(r.status())).isEqualTo(row.get("atteso"));
                    // docs/06 §2: ogni endpoint ha summary e tag = area (Q-341, ADR-046).
                    JsonNode paths = r.json(mapper).path("paths");
                    assertThat(paths.size()).as("operazioni documentate").isGreaterThan(50);
                    List<String> missing = new ArrayList<>();
                    for (Map.Entry<String, JsonNode> path : paths.properties()) {
                        for (Map.Entry<String, JsonNode> op : path.getValue().properties()) {
                            JsonNode o = op.getValue();
                            if (o.path("summary").asString("").isBlank() || o.path("tags").size() != 1) {
                                missing.add(op.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey());
                            }
                        }
                    }
                    assertThat(missing).as("operazioni senza summary o con tag diverso da uno").isEmpty();
                }
                default -> throw new IllegalArgumentException(kase);
            }
        });
    }

    // ================= errori RFC 9457 sulle API vere =================

    @TestFactory
    @Order(3)
    Stream<DynamicTest> errors() {
        return plt("errors-hub.csv", row -> {
            String failure = null;
            if (row.is("guasto", "si")) {
                failure = failInserts("member.member", "true", 1);
            }
            try {
                String body = row.blank("corpo") ? null : row.get("corpo").replace("{tag}", uniqueTag());
                Raw r = raw(row.get("metodo"), row.get("percorso"), headers(
                        "X-LH-Actor", row.blank("attore") ? null : row.get("attore"),
                        "Content-Type", row.blank("contentType") ? null : row.get("contentType")), body);
                JsonNode p = r.json(mapper);
                String what = row.id() + " → " + r.status() + " " + r.body();
                assertThat(r.status()).as(what).isEqualTo((int) row.num("stato"));
                assertThat(r.contentType()).as(what).startsWith("application/problem+json");
                assertThat(p.path("type").asString()).as(what).isEqualTo("urn:loyaltyhub:problem:" + row.get("tipo"));
                assertThat(p.path("code").asString()).as(what).isEqualTo(row.get("codice"));
                assertThat(p.path("status").asInt()).as(what).isEqualTo((int) row.num("stato"));
                assertThat(p.path("title").asString()).as(what).isNotBlank();
                assertThat(p.path("detail").asString()).as(what).isNotBlank();
                assertThat(p.path("instance").asString()).as(what).isEqualTo(row.get("percorso").replaceAll("\\?.*$", ""));
                assertThat(r.body()).as(what).doesNotContain("Exception", "guasto simulato", "org.springframework", "SQL");
            } finally {
                if (failure != null) {
                    dropFailure(failure);
                }
            }
        });
    }

    // ================= paginazione {items, page} =================

    @TestFactory
    @Order(4)
    Stream<DynamicTest> pagination() {
        return plt("pagination.csv", row -> {
            String path = row.get("elenco") + (row.get("elenco").contains("?") ? "&" : "?") + row.get("parametri");
            Resp r = send("GET", path.replace("?&", "?").replaceAll("[?&]$", ""), ADMIN, null);
            String what = row.id() + " GET " + path + " → " + r.status() + " " + r.body();
            assertThat(r.status()).as(what).isEqualTo((int) row.num("stato"));
            if (r.status() != 200) {
                assertThat(r.code()).as(what).isEqualTo(row.get("atteso"));
                assertThat(r.body().path("type").asString()).as(what).isEqualTo("urn:loyaltyhub:problem:bad-request");
                return;
            }
            JsonNode page = r.body().path("page");
            assertThat(r.body().path("items").isArray()).as(what).isTrue();
            assertThat(page.path("number").isInt() && page.path("size").isInt() && page.path("totalItems").isNumber()
                    && page.path("totalPages").isInt()).as("forma di page: " + what).isTrue();
            int size = page.path("size").asInt();
            long total = page.path("totalItems").asLong();
            assertThat(r.body().path("items").size()).as(what).isLessThanOrEqualTo(size);
            assertThat(page.path("totalPages").asLong()).as("totalPages = ceil(totalItems/size): " + what)
                    .isEqualTo((total + size - 1) / size);
            assertThat(page.path("number").asInt()).as(what).isEqualTo(0);
            String expected = row.get("atteso");
            if (expected.startsWith("size=")) {
                assertThat("size=" + size).as(what).isEqualTo(expected);
            } else {
                assertThat(size).as("size di default tra 1 e 100: " + what).isBetween(1, 100);
            }
        });
    }

    // ================= X-LH-Actor sull'endpoint ADMIN del reset =================

    @TestFactory
    @Order(5)
    Stream<DynamicTest> actor() {
        return plt("actor-hub.csv", row -> {
            String header = row.get("intestazione");
            String value = switch (header) {
                case "ASSENTE" -> null;
                case "VUOTA" -> "";
                default -> header.replace("«", "").replace("»", "");
            };
            if (row.blank("percorso")) {
                quiet();
                Raw r = raw("POST", "/v1/demo/reset", headers("X-LH-Actor", value), null);
                assertThat(String.valueOf(r.status())).as(row.id() + " " + r.body()).isEqualTo(row.get("atteso").split(" ")[0]);
                if (r.status() == 200) {
                    quiet();
                    JsonNode audit = get("/v1/audit?action=RESET&size=5");
                    JsonNode first = audit.path("items").get(0);
                    assertThat(first.path("actorRole").asString() + ":" + first.path("actorName").asString()).as("attore dell'audit RESET")
                            .isEqualTo(row.get("atteso").split(" ")[1]);
                } else {
                    assertThat(r.json(mapper).path("code").asString()).isEqualTo("FORBIDDEN_ROLE");
                }
            } else {
                Raw r = raw("GET", row.get("percorso"), headers("X-LH-Actor", value), null);
                assertThat(String.valueOf(r.status())).as(row.id() + " " + r.body()).isEqualTo(row.get("atteso"));
            }
        });
    }

    // ================= reset della demo =================

    @TestFactory
    @Order(6)
    Stream<DynamicTest> reset() {
        return plt("reset.csv", row -> {
            switch (row.get("caso")) {
                case "response" -> {
                    quiet();
                    lastResetStarted = Instant.now();
                    Resp r = send("POST", "/v1/demo/reset", ADMIN, null);
                    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
                    quiet();
                    seedFingerprint = fingerprint();
                    List<String> components = new ArrayList<>();
                    r.body().path("reset").forEach(c -> components.add(c.asString()));
                    assertThat(r.body().path("status").asString() + " primo=" + components.get(0) + " componenti="
                            + new TreeSet<>(components)).isEqualTo(row.get("atteso"));
                }
                case "twice" -> {
                    resetDemo();
                    Map<String, List<String>> first = fingerprint();
                    resetDemo();
                    assertSame(fingerprint(), first, "due reset consecutivi");
                    assertSame(first, seedFingerprint, "reset e seed");
                }
                case "afterActivity" -> {
                    Member m = newMember();
                    purchase(m.subject(), 130, todayAt(9, 0));
                    credit("MBR-000002", 500);
                    ok(send("POST", "/v1/portal/redemptions", null, Map.of("memberId", "MBR-000004", "rewardCode", "RWD-COFFEE-5")),
                            202);
                    runScenario("SCN-SMOKE");
                    plantAndPlay(m.id(), "COFFEE");
                    ok(send("POST", "/v1/webhooks/WH-CRM-DEMO/test", ADMIN, null), 200, 201, 202);
                    String tag = uniqueTag().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
                    ok(send("POST", "/v1/event-types", MARKETING, Map.of("code", "tbplt" + tag.toLowerCase(java.util.Locale.ROOT)
                            + ".sent", "name", "Tipo di prova", "category", "SERVICE", "dataSchema", Map.of("type", "object"),
                            "sampleData", Map.of())), 201);
                    ok(send("POST", "/v1/campaigns", MARKETING, Map.ofEntries(
                            Map.entry("code", "CMP-TBPLT-" + tag), Map.entry("name", "Campagna di prova"),
                            Map.entry("memberDescription", "prova"), Map.entry("priority", 100),
                            Map.entry("triggerActionTypes", List.of("purchase.completed")),
                            Map.entry("audience", Map.of("all", true)), Map.entry("conditions", Map.of("op", "all", "rules", List.of())),
                            Map.entry("effects", List.of(Map.of("type", "GRANT_POINTS", "currency", "PTS", "mode", "FIXED", "value", 1))),
                            Map.entry("limits", Map.of()), Map.entry("schedule", Map.of("startAt", "2026-01-01T00:00:00Z")))), 201);
                    ok(send("POST", "/v1/rewards", MARKETING, Map.of("code", "RWD-TBPLT-" + tag, "name", "Premio di prova",
                            "type", "DIGITAL", "band", "F1", "category", "TEMPO", "fulfilment", "INSTANT")), 201);
                    ok(send("POST", "/v1/contents", MARKETING, Map.of("code", "CNT-TBPLT-" + tag, "kind", "CARD",
                            "placement", "HOME_GRID", "title", "Contenuto di prova")), 201);
                    ok(send("POST", "/v1/editions", ADMIN, Map.of("code", "ED-2028", "name", "Edizione di prova",
                            "startDate", "2028-01-01", "endDate", "2028-12-31")), 200, 201);
                    ok(send("POST", "/v1/sources", ADMIN, Map.of("code", "tbplt" + tag.toLowerCase(java.util.Locale.ROOT),
                            "name", "Fonte di prova", "kind", "HTTP", "enabled", true, "allowedTypes", List.of())), 201);
                    quiet();
                    resetDemo();
                    assertSame(fingerprint(), seedFingerprint, "reset dopo attività");
                    assertThat(count("SELECT count(*) FROM member.member WHERE id = ?", m.id())).as("membro nato dopo il seed").isZero();
                }
                case "codes" -> {
                    resetDemo();
                    Map<String, List<String>> fp = fingerprint();
                    assertThat(fp.get("coupon")).as("codici coupon").isEqualTo(seedFingerprint.get("coupon")).isNotEmpty();
                    assertThat(fp.get("istanti")).as("istanti vincenti").isEqualTo(seedFingerprint.get("istanti")).isNotEmpty();
                }
                case "members" -> {
                    resetDemo();
                    assertThat(String.join(" ", strings("SELECT id || '=' || status FROM member.member ORDER BY id")))
                            .isEqualTo(row.get("atteso"));
                }
                case "audit" -> {
                    resetDemo();
                    JsonNode audit = get("/v1/audit?action=RESET&size=10");
                    JsonNode e = audit.path("items").get(0);
                    assertThat(audit.path("page").path("totalItems").asInt() + " " + e.path("actorRole").asString() + ":" + e.path("actorName").asString() + " "
                            + e.path("entityType").asString() + " " + e.path("service").asString()).isEqualTo(row.get("atteso"));
                }
                case "concurrent" -> {
                    quiet();
                    ExecutorService pool = Executors.newFixedThreadPool(2);
                    CountDownLatch start = new CountDownLatch(1);
                    try {
                        List<Future<Integer>> fs = new ArrayList<>();
                        for (int i = 0; i < 2; i++) {
                            fs.add(pool.submit(() -> {
                                start.await();
                                return send("POST", "/v1/demo/reset", ADMIN, null).status();
                            }));
                        }
                        start.countDown();
                        List<Integer> statuses = new ArrayList<>();
                        for (Future<Integer> f : fs) {
                            statuses.add(f.get());
                        }
                        assertThat(statuses).as("stati dei due reset").containsExactly(200, 200);
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    } finally {
                        pool.shutdownNow();
                    }
                    quiet();
                    assertSame(fingerprint(), seedFingerprint, "due reset concorrenti");
                }
                case "outbox" -> {
                    resetDemo();
                    assertThat(count("SELECT count(*) FROM outbox WHERE published_at IS NULL")).as("righe da pubblicare").isZero();
                }
                case "pipeline" -> {
                    resetDemo();
                    Member m = newMember();
                    long before = pts(m.id());
                    purchase(m.subject(), 20, todayAt(9, 0));
                    quiet();
                    assertThat(pts(m.id()) - before).as("punti dell'acquisto dopo il reset").isPositive();
                }
                case "info" -> {
                    Instant before = clock.instant();
                    resetDemo();
                    JsonNode info = ok(send("GET", "/v1/demo/info", ADMIN, null), 200);
                    List<String> profiles = new ArrayList<>();
                    info.path("profiles").forEach(p -> profiles.add(p.asString()));
                    Instant last = Instant.parse(info.path("lastReset").asString());
                    assertThat(last).as("ultimo reset").isAfterOrEqualTo(before.minusSeconds(1));
                    assertThat(info.path("service").asString() + " profili=" + profiles + " versione="
                            + (info.path("version").asString("").isBlank() ? "assente" : "presente")
                            + " membri=" + info.path("counts").path("member.member").asInt())
                            .isEqualTo(row.get("atteso"));
                }
                case "infoForbidden" -> {
                    Resp r = send("GET", "/v1/demo/info", MARKETING, null);
                    assertThat(r.status() + " " + r.code()).isEqualTo(row.get("atteso"));
                }
                default -> throw new IllegalArgumentException(row.get("caso"));
            }
        });
    }

    // ================= requisiti non funzionali misurabili =================

    @TestFactory
    @Order(7)
    Stream<DynamicTest> nonFunctional() {
        return plt("nfr.csv", row -> {
            switch (row.get("caso")) {
                case "latency" -> {
                    List<Long> ms = new ArrayList<>();
                    for (int k = 0; k < 4; k++) {
                        Member m = newMember();
                        for (int i = 0; i < 3 && ms.size() < 10; i++) {
                            long ledgerBefore = count("SELECT count(*) FROM wallet.ledger_entry WHERE member_id = ?", m.id());
                            long start = System.nanoTime();
                            purchase(m.subject(), 10 + i, todayAt(9, i));
                            await("movimento dell'acquisto", 20_000, () ->
                                    count("SELECT count(*) FROM wallet.ledger_entry WHERE member_id = ?", m.id()) > ledgerBefore);
                            ms.add((System.nanoTime() - start) / 1_000_000);
                        }
                    }
                    List<Long> sorted = ms.stream().sorted().toList();
                    long p50 = sorted.get(sorted.size() / 2 - 1);
                    long p95 = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
                    assertThat(p50).as("p50 azione → movimento (ms) su %s", ms).isLessThanOrEqualTo(3_000);
                    assertThat(p95).as("p95 azione → movimento (ms) su %s", ms).isLessThanOrEqualTo(8_000);
                }
                case "order" -> {
                    Member m = newMember();
                    List<String> actions = new ArrayList<>();
                    for (int i = 0; i < 10; i++) {
                        String id = "tb-plt-ord-" + uniqueTag();
                        Resp r = postEvent(id, "partner", "survey.completed", m.subject(), todayAt(8, i),
                                Map.of("surveyId", "SRV-" + i, "score", i));
                        assertThat(r.status()).isEqualTo(202);
                        actions.add(correlationOf(id));
                    }
                    quiet();
                    List<String> evaluated = strings("""
                            SELECT payload->'data'->>'actionId' FROM insight.event_store
                            WHERE member_id = '%s' AND type = 'io.loyaltyhub.fact.campaign.evaluated' ORDER BY kafka_offset
                            """.formatted(m.id())).stream().filter(actions::contains).toList();
                    assertThat(evaluated).as("valutazioni nell'ordine delle azioni del membro").isEqualTo(actions);
                }
                default -> throw new IllegalArgumentException(row.get("caso"));
            }
        });
    }

    // ================= limite di frequenza degli ingressi (docs/11 §11) =================

    @TestFactory
    @Order(8)
    Stream<DynamicTest> rateLimit() {
        return plt("rate-limit.csv", row -> {
            String ip = row.get("ip").equals("-") ? null : row.get("ip");
            int n = (int) row.num("eventi");
            List<Integer> statuses = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String event = """
                        {"specversion":"1.0","id":"tb-plt-rl-%s","source":"urn:loyaltyhub:source:app","type":"app.login.daily",
                         "subject":"member:MBR-999%s","time":"2026-09-24T08:00:00Z","data":{"platform":"WEB"}}
                        """.formatted(uniqueTag(), i % 10);
                Raw r = raw("POST", "/v1/events", headers("Content-Type", "application/json", "X-Forwarded-For", ip), event);
                statuses.add(r.status());
                if (r.status() == 429) {
                    JsonNode p = r.json(mapper);
                    assertThat(p.path("code").asString() + " " + p.path("type").asString())
                            .isEqualTo("RATE_LIMITED urn:loyaltyhub:problem:rate-limited");
                }
            }
            long limited = statuses.stream().filter(s -> s == 429).count();
            assertThat("accettati=" + (n - limited) + " limitati=" + limited).as("stati " + statuses).isEqualTo(row.get("atteso"));
        });
    }

}
