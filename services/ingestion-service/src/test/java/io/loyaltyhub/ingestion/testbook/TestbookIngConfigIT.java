package io.loyaltyhub.ingestion.testbook;

import io.loyaltyhub.ingestion.domain.EventType;
import io.loyaltyhub.ingestion.domain.Scenario;
import io.loyaltyhub.ingestion.infra.EventTypeRepository;
import io.loyaltyhub.ingestion.infra.ScenarioRepository;
import io.loyaltyhub.ingestion.testbook.TestbookIngRows.Row;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-ING — configurazione e porte secondarie (docs/testbook/TB-ING-ingresso.md, aree ETY, FON, TXN, SIM, SCN):
 * tipi azione SYSTEM/CUSTOM (F-ING-06, M6.7, Q-89), registro fonti (F-ING-05), {@code POST /v1/transactions} (F-ING-07,
 * Q-49), simulatore (F-DEMO-03, BO-28) e scenari (F-DEMO-04, docs/10 §8, Q-129, Q-130).
 */
class TestbookIngConfigIT extends TestbookIngHarness {

    private static final String ADMIN = "ADMIN:marta.admin";
    private static final List<String> DOCS10_SCENARIOS = List.of("SCN-ONBOARDING", "SCN-TIER-UP", "SCN-DIGITAL",
            "SCN-REFERRAL", "SCN-WEEKEND-BURST", "SCN-DUPLICATE", "SCN-BAD-EVENT", "SCN-POISON", "SCN-SMOKE");

    @Autowired EventTypeRepository eventTypes;
    @Autowired ScenarioRepository scenarios;

    private static String actorOf(String csv) {
        return csv == null || "-".equals(csv) ? null : csv;
    }

    // ---------- ETY: tipi azione ----------

    @TestFactory
    Stream<DynamicTest> ety() {
        return rows("ety.csv", this::etyRow);
    }

    private ObjectNode customBody(String code) {
        ObjectNode b = mapper.createObjectNode();
        b.put("code", code);
        b.put("name", "Lettura contatore");
        b.put("description", "Lettura inviata dal contatore intelligente");
        b.put("category", "SERVICE");
        b.put("icon", "gauge");
        b.set("dataSchema", json("{\"type\":\"object\",\"required\":[\"meterId\"],\"properties\":{"
                + "\"meterId\":{\"type\":\"string\",\"minLength\":1},\"kind\":{\"type\":\"string\",\"enum\":[\"GAS\",\"POWER\"]}}}"));
        b.set("sampleData", json("{\"meterId\":\"MTR-1\",\"kind\":\"GAS\"}"));
        return b;
    }

    private static String freshCode() {
        return "tbety.c" + seq() + ".sent";
    }

    private Response create(ObjectNode body, String actor) {
        return call("POST", "/v1/event-types", actor, body);
    }

    private Response put(String code, Object body, String actor) {
        return call("PUT", "/v1/event-types/" + code, actor, body);
    }

    private String createdCustom(ObjectNode body) {
        Response r = create(body, ADMIN);
        assertThat(r.status()).as("creazione di appoggio (corpo: %s)", r.body()).isEqualTo(201);
        return body.path("code").asString();
    }

    private Response sendType(String type, JsonNode data) {
        String id = freshEventId();
        return postEvent(event(id, "simulator", type, "member:" + freshMember("ACTIVE").memberId(), Instant.now(), data));
    }

    // TESTBOOK: ambiguo, vedi TB-ING-ETY-009, 010, 015, 016, 022, 027, 029, 034, 057 (atteso = comportamento attuale, marcato AMBIGUO nel CSV)
    void etyRow(Row a) {
        String kase = a.getString(2), actor = actorOf(a.getString(3));
        int http = a.getInteger(4);
        String code = a.getString(5), field = a.getString(6);
        String typeCode = freshCode();
        ObjectNode body = customBody(typeCode);
        EventType systemBefore = eventTypes.findByCode("member.birthday").orElseThrow();
        List<Runnable> after = new ArrayList<>();
        Response r;
        try {
            switch (kase) {
                case "c.valid3", "c.valid4", "c.valid2", "c.onePart", "c.fiveParts", "c.upperSnake", "c.camel",
                     "c.digitStart", "c.len60", "c.len61", "c.blankCode" -> {
                    int n = seq();
                    String c = switch (kase) {
                        case "c.valid2" -> "tbety" + n + ".sent";
                        case "c.valid4" -> "tbety.c" + n + ".meter.sent";
                        case "c.onePart" -> "tbety" + n;
                        case "c.fiveParts" -> "tbety.c" + n + ".a.b.c";
                        case "c.upperSnake" -> "Meter_Reading";
                        case "c.camel" -> "tbety" + n + ".readingSent";
                        case "c.digitStart" -> "1tbety" + n + ".sent";
                        case "c.len60", "c.len61" -> {
                            String prefix = "tbety" + n + ".";
                            yield prefix + "x".repeat((kase.equals("c.len60") ? 60 : 61) - prefix.length());
                        }
                        case "c.blankCode" -> "  ";
                        default -> typeCode;
                    };
                    body.put("code", c);
                    r = create(body, actor);
                    if (http == 201) {
                        after.add(() -> {
                            assertThat(r.text("origin")).isEqualTo("CUSTOM");
                            assertThat(r.body().path("enabled").asBoolean(false)).isTrue();
                        });
                    }
                }
                case "c.dupCustom" -> {
                    createdCustom(body.deepCopy());
                    r = create(body, actor);
                }
                case "c.dupSystem" -> {
                    body.put("code", "purchase.completed");
                    r = create(body, actor);
                }
                case "c.nameBlank", "c.name60", "c.name61" -> {
                    body.put("name", switch (kase) {
                        case "c.nameBlank" -> "   ";
                        case "c.name60" -> "N".repeat(60);
                        default -> "N".repeat(61);
                    });
                    r = create(body, actor);
                }
                case "c.catTransaction", "c.catEngagement", "c.catService", "c.catInternal", "c.catUnknown", "c.catMissing" -> {
                    switch (kase) {
                        case "c.catTransaction" -> body.put("category", "TRANSACTION");
                        case "c.catEngagement" -> body.put("category", "ENGAGEMENT");
                        case "c.catService" -> body.put("category", "SERVICE");
                        case "c.catInternal" -> body.put("category", "INTERNAL");
                        case "c.catUnknown" -> body.put("category", "FOO");
                        default -> body.remove("category");
                    }
                    r = create(body, actor);
                    if (kase.equals("c.catMissing")) {
                        after.add(() -> assertThat(r.text("category")).isEqualTo("ENGAGEMENT"));
                    }
                }
                case "c.schemaMissing", "c.schemaArray", "c.schemaInvalid" -> {
                    switch (kase) {
                        case "c.schemaMissing" -> body.remove("dataSchema");
                        case "c.schemaArray" -> body.set("dataSchema", json("{\"type\":\"array\"}"));
                        default -> body.set("dataSchema",
                                json("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"nonsense\"}}}"));
                    }
                    body.set("sampleData", json("{}"));
                    r = create(body, actor);
                }
                case "c.sampleInvalid" -> {
                    body.set("sampleData", json("{\"kind\":\"GAS\"}"));
                    r = create(body, actor);
                }
                case "c.sampleMissing" -> {
                    body.set("dataSchema", json("{\"type\":\"object\",\"properties\":{\"meterId\":{\"type\":\"string\"}}}"));
                    body.remove("sampleData");
                    r = create(body, actor);
                    after.add(() -> assertThat(r.body().path("sampleData")).isEqualTo(json("{}")));
                }
                case "c.disabledAtCreate" -> {
                    body.put("enabled", false);
                    r = create(body, actor);
                    after.add(() -> {
                        assertThat(r.body().path("enabled").asBoolean(true)).isFalse();
                        assertThat(sendType(typeCode, json("{\"meterId\":\"MTR-1\"}")).text("rejectCode")).isEqualTo("UNKNOWN_TYPE");
                    });
                }
                case "c.defaultEnabled" -> {
                    body.remove("enabled");
                    r = create(body, actor);
                    after.add(() -> assertThat(r.body().path("enabled").asBoolean(false)).isTrue());
                }
                case "c.role" -> r = create(body, actor);
                case "c.audit" -> {
                    r = create(body, actor);
                    after.add(() -> {
                        List<JsonNode> audit = audits("event_type:" + typeCode);
                        assertThat(audit).hasSize(1);
                        assertThat(audit.getFirst().path("data").path("action").asString()).isEqualTo("CREATE");
                        assertThat(audit.getFirst().path("lhactor").asString()).isEqualTo(actor);
                    });
                }
                case "c.usable" -> {
                    r = create(body, actor);
                    after.add(() -> {
                        assertThat(sendType(typeCode, json("{\"meterId\":\"MTR-9\",\"kind\":\"POWER\"}")).text("status"))
                                .isEqualTo("ACCEPTED");
                        Response bad = sendType(typeCode, json("{\"kind\":\"POWER\"}"));
                        assertThat(bad.text("rejectCode")).isEqualTo("INVALID_DATA");
                        assertThat(bad.text("detail")).contains("meterId");
                    });
                }
                case "c.fields" -> {
                    r = create(body, actor);
                    after.add(() -> {
                        JsonNode fields = call("GET", "/v1/event-types/" + typeCode + "/fields", null, null).body();
                        Map<String, JsonNode> byPath = byPath(fields);
                        assertThat(byPath.get("data.meterId").path("type").asString()).isEqualTo("string");
                        assertThat(byPath.get("data.meterId").path("required").asBoolean(false)).isTrue();
                        assertThat(byPath.get("data.kind").path("enum").toString()).isEqualTo("[\"GAS\",\"POWER\"]");
                    });
                }
                case "c.listed" -> {
                    r = create(body, actor);
                    after.add(() -> {
                        JsonNode list = call("GET", "/v1/event-types", null, null).body();
                        JsonNode mine = find(list, typeCode);
                        assertThat(mine).isNotNull();
                        assertThat(mine.path("origin").asString()).isEqualTo("CUSTOM");
                    });
                }
                case "u.all" -> {
                    createdCustom(body.deepCopy());
                    ObjectNode upd = mapper.createObjectNode();
                    upd.put("name", "Lettura rinominata");
                    upd.put("description", "Nuova descrizione");
                    upd.put("category", "TRANSACTION");
                    upd.put("icon", "zap");
                    upd.put("enabled", false);
                    upd.set("dataSchema", json("{\"type\":\"object\",\"properties\":{\"reading\":{\"type\":\"number\"}}}"));
                    upd.set("sampleData", json("{\"reading\":1}"));
                    r = put(typeCode, upd, actor);
                    after.add(() -> {
                        JsonNode v = r.body();
                        assertThat(v.path("name").asString()).isEqualTo("Lettura rinominata");
                        assertThat(v.path("description").asString()).isEqualTo("Nuova descrizione");
                        assertThat(v.path("category").asString()).isEqualTo("TRANSACTION");
                        assertThat(v.path("icon").asString()).isEqualTo("zap");
                        assertThat(v.path("enabled").asBoolean(true)).isFalse();
                        assertThat(v.path("dataSchema")).isEqualTo(upd.path("dataSchema"));
                        assertThat(v.path("sampleData")).isEqualTo(upd.path("sampleData"));
                        assertThat(v.path("code").asString()).isEqualTo(typeCode);
                        List<JsonNode> audit = audits("event_type:" + typeCode);
                        assertThat(audit).hasSize(2);
                        assertThat(audit.getLast().path("data").path("action").asString()).isEqualTo("UPDATE");
                        assertThat(audit.getLast().path("lhactor").asString()).isEqualTo(actor);
                    });
                }
                case "u.codeDiff", "u.codeSame", "u.badCategory" -> {
                    createdCustom(body.deepCopy());
                    ObjectNode upd = body.deepCopy();
                    if (kase.equals("u.codeDiff")) {
                        upd.put("code", freshCode());
                    } else if (kase.equals("u.badCategory")) {
                        upd.put("category", "INTERNAL");
                    }
                    r = put(typeCode, upd, actor);
                }
                case "u.schemaNow" -> {
                    body.set("dataSchema", json("{\"type\":\"object\"}"));
                    body.set("sampleData", json("{}"));
                    createdCustom(body.deepCopy());
                    assertThat(sendType(typeCode, json("{}")).text("status")).isEqualTo("ACCEPTED");
                    ObjectNode upd = body.deepCopy();
                    upd.set("dataSchema", json("{\"type\":\"object\",\"required\":[\"reading\"]}"));
                    upd.set("sampleData", json("{\"reading\":3}"));
                    r = put(typeCode, upd, actor);
                    after.add(() -> {
                        Response bad = sendType(typeCode, json("{}"));
                        assertThat(bad.text("rejectCode")).isEqualTo("INVALID_DATA");
                        assertThat(bad.text("detail")).contains("reading");
                    });
                }
                case "u.disable" -> {
                    createdCustom(body.deepCopy());
                    ObjectNode off = body.deepCopy();
                    off.put("enabled", false);
                    r = put(typeCode, off, actor);
                    after.add(() -> {
                        assertThat(sendType(typeCode, json("{\"meterId\":\"M\"}")).text("rejectCode")).isEqualTo("UNKNOWN_TYPE");
                        ObjectNode on = body.deepCopy();
                        on.put("enabled", true);
                        assertThat(put(typeCode, on, actor).status()).isEqualTo(200);
                        assertThat(sendType(typeCode, json("{\"meterId\":\"M\"}")).text("status")).isEqualTo("ACCEPTED");
                    });
                }
                case "u.notFound" -> r = put("tbety.nope" + seq() + ".sent", body, actor);
                case "u.role" -> {
                    createdCustom(body.deepCopy());
                    r = put(typeCode, body, actor);
                }
                case "s.role" -> r = put("member.birthday", Map.of("name", "Compleanno (modificato)"), actor);
                case "s.labels" -> {
                    r = put("member.birthday", Map.of("name", "Compleanno del socio", "description", "Nuova", "icon", "party"), actor);
                    after.add(() -> {
                        JsonNode v = r.body();
                        assertThat(v.path("name").asString()).isEqualTo("Compleanno del socio");
                        assertThat(v.path("description").asString()).isEqualTo("Nuova");
                        assertThat(v.path("icon").asString()).isEqualTo("party");
                        assertThat(v.path("origin").asString()).isEqualTo("SYSTEM");
                        assertThat(v.path("category").asString()).isEqualTo(systemBefore.category());
                        assertThat(v.path("dataSchema")).isEqualTo(json(systemBefore.dataSchema()));
                    });
                }
                case "s.disable" -> {
                    r = put("member.birthday", Map.of("enabled", false), actor);
                    after.add(() -> assertThat(sendType("member.birthday", json("{\"age\":30}")).text("rejectCode"))
                            .isEqualTo("UNKNOWN_TYPE"));
                }
                case "s.category" -> r = put("member.birthday", Map.of("category", "SERVICE"), actor);
                case "s.schema" -> r = put("member.birthday", Map.of("dataSchema", json("{\"type\":\"object\"}")), actor);
                case "s.sample" -> r = put("member.birthday", Map.of("sampleData", json("{\"age\":1}")), actor);
                case "s.sameSchema" -> r = put("member.birthday", Map.of("dataSchema", json(systemBefore.dataSchema())), actor);
                case "s.code" -> r = put("member.birthday", Map.of("code", "member.birthday2"), actor);
                case "s.nameBlank" -> {
                    r = put("member.birthday", Map.of("name", "   "), actor);
                    after.add(() -> assertThat(r.text("name")).isEqualTo(systemBefore.name()));
                }
                case "f.system" -> {
                    r = call("GET", "/v1/event-types/purchase.completed/fields", null, null);
                    after.add(() -> {
                        Map<String, JsonNode> byPath = byPath(r.body());
                        assertThat(byPath.get("data.amount").path("type").asString()).isEqualTo("number");
                        assertThat(byPath.get("data.amount").path("required").asBoolean(false)).isTrue();
                        assertThat(byPath.get("data.channel").path("enum").toString()).isEqualTo("[\"ONLINE\",\"STORE\",\"APP\"]");
                        assertThat(byPath).containsKey("data.items[*].sku");
                        assertThat(byPath.get("data.items[*].quantity").path("type").asString()).isEqualTo("integer");
                    });
                }
                case "f.notFound" -> r = call("GET", "/v1/event-types/tb.nope" + seq() + "/fields", null, null);
                case "l.system" -> {
                    r = call("GET", "/v1/event-types", null, null);
                    after.add(() -> {
                        Set<String> system = new HashSet<>();
                        for (JsonNode t : r.body()) {
                            if ("SYSTEM".equals(t.path("origin").asString())) {
                                system.add(t.path("code").asString());
                            }
                        }
                        Set<String> seed = new HashSet<>();
                        for (JsonNode t : json(readSeed("event-types.json"))) {
                            seed.add(t.path("code").asString());
                        }
                        assertThat(seed).hasSize(19);
                        assertThat(system).isEqualTo(seed);
                    });
                }
                default -> throw new IllegalArgumentException(kase);
            }
            assertThat(r.status()).as("HTTP (corpo: %s)", r.body()).isEqualTo(http);
            if (!"-".equals(code)) {
                assertThat(r.text("code")).isEqualTo(code);
            }
            if (!"-".equals(field)) {
                assertThat(r.body().path("errors").toString()).as("errore sul campo").contains("\"" + field + "\"");
            }
            for (Runnable check : after) {
                check.run();
            }
        } finally {
            eventTypes.save(systemBefore);
        }
    }

    private static Map<String, JsonNode> byPath(JsonNode fields) {
        Map<String, JsonNode> m = new LinkedHashMap<>();
        for (JsonNode f : fields) {
            m.put(f.path("path").asString(), f);
        }
        return m;
    }

    private static JsonNode find(JsonNode list, String code) {
        for (JsonNode t : list) {
            if (code.equals(t.path("code").asString())) {
                return t;
            }
        }
        return null;
    }

    private static String readSeed(String file) {
        try (var in = TestbookIngConfigIT.class.getResourceAsStream("/seed/" + file)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- FON: registro fonti via API ----------

    @TestFactory
    Stream<DynamicTest> fon() {
        return rows("fon.csv", this::fonRow);
    }

    void fonRow(Row a) {
        String kase = a.getString(2);
        switch (kase) {
            case "list" -> {
                JsonNode list = call("GET", "/v1/sources", null, null).body();
                Map<String, JsonNode> byCode = new LinkedHashMap<>();
                for (JsonNode s : list) {
                    byCode.put(s.path("code").asString(), s);
                }
                for (JsonNode seed : json(readSeed("sources.json"))) {
                    JsonNode s = byCode.get(seed.path("code").asString());
                    assertThat(s).as("fonte " + seed.path("code").asString()).isNotNull();
                    assertThat(s.path("enabled")).isEqualTo(seed.path("enabled"));
                    assertThat(s.path("allowedTypes")).isEqualTo(seed.path("allowedTypes"));
                    assertThat(s.path("kind")).isEqualTo(seed.path("kind"));
                }
            }
            case "put" -> {
                String src = freshSource(true, List.of());
                Response r = call("PUT", "/v1/sources/" + src, ADMIN,
                        Map.of("code", src, "name", "Fonte", "kind", "HTTP", "enabled", false, "allowedTypes", List.of()));
                assertThat(r.status()).as("HTTP (corpo: %s)", r.body()).isEqualTo(200);
                String id = freshEventId();
                Response ev = postEvent(event(id, src, "purchase.completed", "member:" + freshMember("ACTIVE").memberId(),
                        Instant.now(), purchaseData("O", 1)));
                assertOutcome(ev, id, "REJECTED", "SOURCE_DISABLED");
            }
            case "post" -> {
                String code = "tbfon" + seq();
                Response r = call("POST", "/v1/sources", ADMIN,
                        Map.of("code", code, "name", "Nuova fonte", "kind", "HTTP", "enabled", true, "allowedTypes", List.of()));
                assertThat(r.status()).as("HTTP (corpo: %s)", r.body()).isEqualTo(201);
            }
            case "putMarketing" -> {
                String src = freshSource(true, List.of());
                Response r = call("PUT", "/v1/sources/" + src, "MARKETING:luca.marketing", Map.of("enabled", false));
                assertThat(r.status()).as("HTTP (corpo: %s)", r.body()).isEqualTo(403);
                assertThat(r.text("code")).isEqualTo("FORBIDDEN_ROLE");
            }
            default -> throw new IllegalArgumentException(kase);
        }
    }

    // ---------- TXN: POST /v1/transactions ----------

    @TestFactory
    Stream<DynamicTest> txn() {
        return rows("txn.csv", this::txnRow);
    }

    private ObjectNode txnBody(String orderId, String memberRef) {
        ObjectNode t = mapper.createObjectNode();
        t.put("source", "ecommerce");
        t.put("orderId", orderId);
        t.put("memberRef", memberRef);
        t.put("amount", 64.9);
        t.put("currency", "EUR");
        t.put("channel", "ONLINE");
        t.set("items", json("[{\"sku\":\"SKU-100\",\"category\":\"casa\",\"quantity\":1,\"unitPrice\":64.9}]"));
        t.put("occurredAt", Instant.now().toString());
        return t;
    }

    private Response postTxn(Object body, String actor) {
        return call("POST", "/v1/transactions", actor, body);
    }

    // TESTBOOK: ambiguo, vedi TB-ING-TXN-003, TB-ING-TXN-007, TB-ING-TXN-012 (atteso = comportamento attuale, marcato AMBIGUO nel CSV)
    void txnRow(Row a) {
        String kase = a.getString(2);
        int http = a.getInteger(3);
        String status = a.getString(4), code = a.getString(5);
        String orderId = "TB-ORD-" + seq() + "-" + System.nanoTime() % 100_000;
        Fresh m = freshMember("ACTIVE");
        ObjectNode t = txnBody(orderId, "member:" + m.memberId());
        String eventId = "txn-" + orderId;
        String src = "ecommerce";
        Object body = t;
        String actor = null;
        Runnable extra = () -> {
        };
        switch (kase) {
            case "purchase", "purchaseExplicit", "purchaseLower" -> {
                t.put("memberRef", "external:CRM-102");
                if (kase.equals("purchaseExplicit")) {
                    t.put("kind", "PURCHASE");
                } else if (kase.equals("purchaseLower")) {
                    t.put("kind", "purchase");
                }
                String pid = eventId;
                extra = () -> {
                    JsonNode p = published(src, pid);
                    assertThat(p.path("type").asString()).isEqualTo("io.loyaltyhub.action.purchase.completed");
                    assertThat(p.path("subject").asString()).isEqualTo("member:MBR-000002");
                    assertThat(p.path("data")).isEqualTo(json("{\"orderId\":\"" + orderId + "\",\"amount\":64.9,"
                            + "\"currency\":\"EUR\",\"channel\":\"ONLINE\",\"items\":[{\"sku\":\"SKU-100\",\"category\":\"casa\","
                            + "\"quantity\":1,\"unitPrice\":64.9}]}"));
                };
            }
            case "return", "returnNoCurrency" -> {
                t.put("kind", "RETURN");
                if (kase.equals("returnNoCurrency")) {
                    t.remove("currency");
                }
                eventId = "txn-return-" + orderId;
                String rid = eventId;
                extra = () -> {
                    JsonNode p = published(src, rid);
                    assertThat(p.path("type").asString()).isEqualTo("io.loyaltyhub.action.purchase.returned");
                    assertThat(p.path("data")).isEqualTo(json("{\"orderId\":\"" + orderId + "\",\"amount\":64.9}"));
                };
            }
            case "refund" -> t.put("kind", "REFUND");
            case "noCurrency" -> t.remove("currency");
            case "noSource" -> t.remove("source");
            case "noOrderId" -> t.remove("orderId");
            case "blankOrderId" -> t.put("orderId", "   ");
            case "noMemberRef" -> t.remove("memberRef");
            case "noAmount" -> t.remove("amount");
            case "emptyBody" -> body = "";
            case "amountText" -> body = t.toString().replace("\"amount\":64.9", "\"amount\":\"abc\"");
            case "sameOrder" -> assertOutcome(postTxn(t, null), eventId, "ACCEPTED", "-");
            case "twoSources" -> {
                assertOutcome(postTxn(t, null), eventId, "ACCEPTED", "-");
                t.put("source", "app");
                extra = () -> {
                    assertThat(publications("ecommerce", "txn-" + orderId)).isEqualTo(1);
                    assertThat(publications("app", "txn-" + orderId)).isEqualTo(1);
                };
            }
            case "purchaseAndReturn" -> {
                assertOutcome(postTxn(t.deepCopy(), null), eventId, "ACCEPTED", "-");
                t.put("kind", "RETURN");
                eventId = "txn-return-" + orderId;
            }
            case "negative" -> t.put("amount", -5);
            case "zero" -> t.put("amount", 0);
            case "noOccurredAt" -> {
                Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
                CLOCK.set(now);
                t.remove("occurredAt");
                String id = eventId;
                extra = () -> assertThat(Instant.parse(published(src, id).path("time").asString())).isEqualTo(now);
            }
            case "old" -> t.put("occurredAt", Instant.now().minus(Duration.ofDays(40)).toString());
            case "posLegacy" -> t.put("source", "pos-legacy");
            case "returnFromApp" -> {
                t.put("source", "app");
                t.put("kind", "RETURN");
                eventId = "txn-return-" + orderId;
            }
            case "unknownMember" -> t.put("memberRef", "external:NOPE-" + seq());
            case "minimal" -> {
                t.remove("channel");
                t.remove("items");
                String id = eventId;
                extra = () -> {
                    JsonNode d = published(src, id).path("data");
                    assertThat(d.has("channel")).isFalse();
                    assertThat(d.has("items")).isFalse();
                };
            }
            case "badChannel" -> t.put("channel", "KIOSK");
            case "badItem" -> t.set("items", json("[{\"sku\":\"SKU-100\",\"quantity\":-1}]"));
            case "noGuard" -> actor = "ANALYST:sara.analyst";
            default -> throw new IllegalArgumentException(kase);
        }
        Response r = postTxn(body, actor);
        assertThat(r.status()).as("HTTP (corpo: %s)", r.body()).isEqualTo(http);
        if (http == 400) {
            assertThat(r.body().path("status").asInt()).isEqualTo(400);
            assertThat(rowsByEventId("txn-" + orderId) + rowsByEventId("txn-return-" + orderId)).as("nulla salvato").isZero();
            return;
        }
        assertThat(r.text("eventId")).isEqualTo(eventId);
        if (kase.equals("twoSources")) {
            // stesso id su due fonti: il conteggio per id somma le due pubblicazioni, si verifica per fonte in extra.
            assertThat(r.text("status")).isEqualTo(status);
            extra.run();
            return;
        }
        assertOutcome(r, eventId, status, code);
        if (kase.equals("purchase")) {
            assertThat(r.text("memberId")).isEqualTo("MBR-000002");
        }
        if (status.equals("DUPLICATE")) {
            assertThat(publicationsById(eventId)).isEqualTo(1);
        }
        extra.run();
    }

    // ---------- SIM: simulatore ----------

    @TestFactory
    Stream<DynamicTest> sim() {
        return rows("sim.csv", this::simRow);
    }

    private Response fire(Map<String, Object> body, String actor) {
        return call("POST", "/v1/demo/simulator/fire", actor, body);
    }

    // TESTBOOK: ambiguo, vedi TB-ING-SIM-011…TB-ING-SIM-013 (atteso = comportamento attuale, marcato AMBIGUO nel CSV)
    void simRow(Row a) {
        String kase = a.getString(2);
        Fresh m = freshMember("ACTIVE");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("memberId", m.memberId());
        body.put("type", "app.login.daily");
        body.put("data", Map.of("platform", "WEB"));
        if (kase.startsWith("role:")) {
            String actor = actorOf(kase.substring(5));
            Response r = fire(body, actor);
            boolean allowed = actor != null && !actor.startsWith("ANALYST");
            assertThat(r.status()).as("HTTP (corpo: %s)", r.body()).isEqualTo(allowed ? 200 : 403);
            if (allowed) {
                JsonNode first = r.body().get(0);
                assertThat(first.path("status").asString()).isEqualTo("ACCEPTED");
                assertThat(first.path("eventId").asString()).isNotBlank();
                assertThat(first.path("correlationId").asString()).isEqualTo(first.path("eventId").asString());
            } else {
                assertThat(r.text("code")).isEqualTo("FORBIDDEN_ROLE");
            }
            return;
        }
        if (kase.startsWith("count:")) {
            String[] p = kase.split(":");
            if (!"-".equals(p[1])) {
                body.put("count", Integer.valueOf(p[1]));
            }
            Response r = fire(body, ADMIN);
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.body().size()).isEqualTo(Integer.parseInt(p[2]));
            Set<String> ids = new HashSet<>();
            for (JsonNode x : r.body()) {
                ids.add(x.path("eventId").asString());
                assertThat(x.path("status").asString()).isEqualTo("ACCEPTED");
            }
            assertThat(ids).hasSize(Integer.parseInt(p[2]));
            return;
        }
        switch (kase) {
            case "defaultSource", "origin" -> {
                String id = fire(body, ADMIN).body().get(0).path("eventId").asString();
                Map<String, Object> row = lastRow(id);
                if (kase.equals("defaultSource")) {
                    assertThat(row.get("source_code")).isEqualTo("simulator");
                    assertThat(row.get("status")).isEqualTo("ACCEPTED");
                } else {
                    assertThat(row.get("origin")).isEqualTo("SIMULATOR");
                }
            }
            case "sample", "variation" -> {
                body.put("type", "survey.completed");
                body.remove("data");
                String id1 = fire(body, ADMIN).body().get(0).path("eventId").asString();
                JsonNode d1 = published("simulator", id1).path("data");
                JsonNode sample = json(eventTypes.sampleData("survey.completed").orElseThrow());
                if (kase.equals("sample")) {
                    assertThat(d1.propertyNames()).containsExactlyInAnyOrderElementsOf(sample.propertyNames());
                } else {
                    String id2 = fire(body, ADMIN).body().get(0).path("eventId").asString();
                    JsonNode d2 = published("simulator", id2).path("data");
                    assertThat(d2).as("piccole variazioni casuali del sample_data").isNotEqualTo(d1);
                }
            }
            case "now" -> {
                Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
                CLOCK.set(now);
                String id = fire(body, ADMIN).body().get(0).path("eventId").asString();
                assertThat(Instant.parse(published("simulator", id).path("time").asString())).isEqualTo(now);
            }
            case "occurredAt" -> {
                Instant when = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
                body.put("occurredAt", when.toString());
                String id = fire(body, ADMIN).body().get(0).path("eventId").asString();
                assertThat(Instant.parse(published("simulator", id).path("time").asString())).isEqualTo(when);
            }
            case "pipelineType" -> {
                body.put("source", "ecommerce");
                body.put("type", "survey.completed");
                body.put("data", Map.of("surveyId", "SRV-1"));
                JsonNode x = fire(body, ADMIN).body().get(0);
                assertThat(x.path("status").asString()).isEqualTo("REJECTED");
                assertThat(x.path("rejectCode").asString()).isEqualTo("TYPE_NOT_ALLOWED");
            }
            case "pipelineMember" -> {
                body.put("memberId", freshMember("BLOCKED").memberId());
                JsonNode x = fire(body, ADMIN).body().get(0);
                assertThat(x.path("status").asString()).isEqualTo("REJECTED");
                assertThat(x.path("rejectCode").asString()).isEqualTo("MEMBER_NOT_ACTIVE");
            }
            default -> throw new IllegalArgumentException(kase);
        }
    }

    // ---------- SCN: scenari ----------

    @TestFactory
    Stream<DynamicTest> scn() {
        return rows("scn.csv", this::scnRow);
    }

    private String startRun(String code, String actor) {
        Response r = call("POST", "/v1/demo/scenarios/" + code + "/run", actor, null);
        assertThat(r.status()).as("avvio (corpo: %s)", r.body()).isEqualTo(202);
        return r.text("runId");
    }

    private JsonNode awaitRun(String runId) {
        JsonNode[] last = new JsonNode[1];
        waitFor(() -> {
            last[0] = call("GET", "/v1/demo/scenario-runs/" + runId, null, null).body();
            return !"RUNNING".equals(last[0].path("status").asString());
        }, Duration.ofSeconds(60));
        return last[0];
    }

    private JsonNode runToEnd(String code) {
        return awaitRun(startRun(code, ADMIN));
    }

    private static List<String> results(JsonNode run, String field) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : run.path("results")) {
            JsonNode v = r.path(field);
            out.add(v.isMissingNode() || v.isNull() ? null : v.asString());
        }
        return out;
    }

    private String customScenario(ArrayNode steps) {
        String code = "SCN-TB-" + seq();
        scenarios.upsert(new Scenario(code, "Scenario testbook", null, null, null, steps));
        return code;
    }

    private ObjectNode step(String type, JsonNode data) {
        ObjectNode s = mapper.createObjectNode();
        s.put("delayMs", 0);
        s.put("memberId", "MBR-000002");
        s.put("type", type);
        s.put("source", "app");
        s.set("data", data);
        s.put("note", "passo testbook");
        return s;
    }

    void scnRow(Row a) {
        String kase = a.getString(2);
        if (kase.startsWith("run:")) {
            String code = kase.substring(4);
            JsonNode run = runToEnd(code);
            assertThat(run.path("status").asString()).isEqualTo("DONE");
            assertThat(run.path("stepsDone").asInt()).isEqualTo(run.path("stepsTotal").asInt());
            assertThat(results(run, "ok")).as("ogni passo come atteso").allMatch("true"::equals);
            List<String> statuses = results(run, "status");
            switch (code) {
                case "SCN-BAD-EVENT" -> {
                    assertThat(statuses).containsExactly("REJECTED", "REJECTED", "UNMATCHED", "REJECTED");
                    assertThat(results(run, "rejectCode")).containsExactly("INVALID_DATA", "SOURCE_DISABLED", null, "MEMBER_NOT_ACTIVE");
                }
                case "SCN-DUPLICATE" -> {
                    assertThat(statuses).containsExactly("ACCEPTED", "DUPLICATE");
                    assertThat(publicationsById(results(run, "eventId").getFirst())).as("un solo accredito").isEqualTo(1);
                }
                case "SCN-ONBOARDING" -> assertThat(results(run, "type"))
                        .containsExactly("app.login.daily", "member.profile.completed", "purchase.completed");
                case "SCN-WEEKEND-BURST" -> assertThat(statuses).hasSize(12).allMatch("ACCEPTED"::equals);
                case "SCN-DIGITAL" -> assertThat(results(run, "type")).containsExactly("ebill.activated", "directdebit.activated");
                default -> assertThat(statuses).allMatch("ACCEPTED"::equals);
            }
            return;
        }
        if (kase.startsWith("role:")) {
            String actor = actorOf(kase.substring(5));
            Response r = call("POST", "/v1/demo/scenarios/SCN-SMOKE/run", actor, null);
            boolean allowed = actor != null && !actor.startsWith("ANALYST");
            assertThat(r.status()).as("HTTP (corpo: %s)", r.body()).isEqualTo(allowed ? 202 : 403);
            if (allowed) {
                assertThat(awaitRun(r.text("runId")).path("status").asString()).isEqualTo("DONE");
            }
            return;
        }
        switch (kase) {
            case "list" -> {
                List<String> codes = new ArrayList<>();
                for (JsonNode s : call("GET", "/v1/demo/scenarios", null, null).body()) {
                    codes.add(s.path("code").asString());
                }
                assertThat(codes).containsAll(DOCS10_SCENARIOS);
            }
            case "rerunDuplicate" -> {
                JsonNode first = runToEnd("SCN-DUPLICATE");
                JsonNode second = runToEnd("SCN-DUPLICATE");
                assertThat(results(first, "status")).containsExactly("ACCEPTED", "DUPLICATE");
                assertThat(results(second, "status")).containsExactly("ACCEPTED", "DUPLICATE");
                assertThat(results(second, "eventId").getFirst()).isNotEqualTo(results(first, "eventId").getFirst());
            }
            case "unknown" -> assertThat(call("POST", "/v1/demo/scenarios/SCN-NOPE/run", ADMIN, null).status()).isEqualTo(404);
            case "unknownRun" -> assertThat(call("GET", "/v1/demo/scenario-runs/NOPE-" + seq(), null, null).status()).isEqualTo(404);
            case "progress" -> {
                JsonNode run = runToEnd("SCN-DUPLICATE");
                assertThat(run.path("stepsTotal").asInt()).isEqualTo(2);
                assertThat(run.path("stepsDone").asInt()).isEqualTo(2);
                assertThat(run.path("actor").asString()).isEqualTo(ADMIN);
                assertThat(run.path("finishedAt").isMissingNode()).isFalse();
                for (JsonNode step : run.path("results")) {
                    assertThat(step.path("correlationId").asString()).isNotBlank().isEqualTo(step.path("eventId").asString());
                }
            }
            case "origin" -> {
                JsonNode run = runToEnd("SCN-SMOKE");
                assertThat(lastRow(results(run, "eventId").getFirst()).get("origin")).isEqualTo("SIMULATOR");
            }
            case "delayCap" -> {
                ArrayNode steps = mapper.createArrayNode();
                ObjectNode s = step("app.login.daily", json("{\"platform\":\"WEB\"}"));
                s.put("delayMs", 12_000);
                steps.add(s);
                long start = System.currentTimeMillis();
                JsonNode run = awaitRun(startRun(customScenario(steps), ADMIN));
                long elapsed = System.currentTimeMillis() - start;
                assertThat(run.path("status").asString()).isEqualTo("DONE");
                assertThat(elapsed).as("attesa limitata a 10 s").isBetween(9_500L, 11_900L);
            }
            case "expectMismatch" -> {
                ArrayNode steps = mapper.createArrayNode();
                ObjectNode s = step("app.login.daily", json("{\"platform\":\"WEB\"}"));
                s.put("expect", "DUPLICATE");
                steps.add(s);
                JsonNode run = runToEnd(customScenario(steps));
                // TESTBOOK: ambiguo, vedi TB-ING-SCN-023
                assertThat(run.path("status").asString()).isEqualTo("DONE");
                assertThat(results(run, "ok")).containsExactly("false");
                assertThat(results(run, "status")).containsExactly("ACCEPTED");
            }
            case "atNow", "atInvalid" -> {
                ArrayNode steps = mapper.createArrayNode();
                ObjectNode s = step("app.login.daily", json("{\"platform\":\"WEB\"}"));
                s.put("at", kase.equals("atNow") ? "@now" : "domani");
                steps.add(s);
                JsonNode run = runToEnd(customScenario(steps));
                if (kase.equals("atNow")) {
                    assertThat(run.path("status").asString()).isEqualTo("DONE");
                    assertThat(results(run, "status")).containsExactly("ACCEPTED");
                } else {
                    // TESTBOOK: ambiguo, vedi TB-ING-SCN-025
                    assertThat(run.path("status").asString()).isEqualTo("FAILED");
                }
            }
            case "noSource" -> {
                ArrayNode steps = mapper.createArrayNode();
                ObjectNode s = step("app.login.daily", json("{\"platform\":\"WEB\"}"));
                s.remove("source");
                steps.add(s);
                JsonNode run = runToEnd(customScenario(steps));
                // TESTBOOK: ambiguo, vedi TB-ING-SCN-026
                assertThat(lastRow(results(run, "eventId").getFirst()).get("source_code")).isEqualTo("simulator");
            }
            default -> throw new IllegalArgumentException(kase);
        }
    }
}
