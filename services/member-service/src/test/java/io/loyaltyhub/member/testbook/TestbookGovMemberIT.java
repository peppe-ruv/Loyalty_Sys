package io.loyaltyhub.member.testbook;

import io.loyaltyhub.common.event.JsonSchemaValidator;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-GOV §8–§11 — member-service via API (docs/servizi/member-service.md §3–§5): stati del membro (MST), guardie per
 * ruolo degli endpoint (MRL), anonimizzazione (ANO), definizioni di attributi in uso (ATU), segmenti (SEG).
 * <p>Un solo contesto Spring (profilo {@code demo}, Postgres embedded, Kafka embedded); ogni riga crea i propri membri,
 * chiavi e segmenti con identificativi nuovi, così non dipende dallo stato mutevole dei seed. Fatti e audit si leggono
 * dall'outbox, scritto nella stessa transazione dell'operazione (docs/06 §1): nessuna attesa.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "loyaltyhub.member.segments.reannounce-delay-ms=0")
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestbookGovMemberIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String ADMIN = "ADMIN";
    private static final String STATUS_CHANGED = "io.loyaltyhub.fact.member.status.changed";
    private static final String UPDATED = "io.loyaltyhub.fact.member.updated";
    private static final String ENTERED = "io.loyaltyhub.fact.member.segment.entered";
    private static final String LEFT = "io.loyaltyhub.fact.member.segment.left";
    private static final String AUDIT = "io.loyaltyhub.audit.entry";

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger seq = new AtomicInteger();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonSchemaValidator validator;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=member");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    // ======================================================================= MST
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gov/member-status.csv", numLinesToSkip = 1)
    void status(String id, String description, String scenario, String from, String target, String expected) {
        // Righe AMBIGUO (stesso stato, minuscolo, precedenza 409/400, filtro CLOSED) — TESTBOOK: ambiguo, vedi TB-GOV §13
        String got = switch (scenario) {
            case "TRANSITION" -> {
                String m = memberIn(from);
                long before = facts(STATUS_CHANGED, m).size();
                Map<String, Object> body = new HashMap<>();
                body.put("status", "NULL".equals(target) ? null : target);
                body.put("reason", "prova testbook");
                Resp r = http(HttpMethod.POST, "/v1/members/" + m + "/status", ADMIN, body);
                long delta = facts(STATUS_CHANGED, m).size() - before;
                yield (r.ok() ? r.status + ":" + member(m).path("status").asString() : r.outcome()) + ":" + delta;
            }
            case "REASON_ABSENT" -> {
                String m = newMember();
                http(HttpMethod.POST, "/v1/members/" + m + "/status", ADMIN, Map.of("status", "BLOCKED"));
                JsonNode data = last(facts(STATUS_CHANGED, m)).path("data");
                String reason = data.path("reason").asString("");
                yield reason.isEmpty() ? "vuoto" : reason;
            }
            case "REASON_TEXT" -> {
                String m = newMember();
                http(HttpMethod.POST, "/v1/members/" + m + "/status", ADMIN, Map.of("status", "BLOCKED", "reason", "Sospetta frode"));
                String reason = last(facts(STATUS_CHANGED, m)).path("data").path("reason").asString("");
                boolean inAudit = audits("MEMBER:" + m).stream()
                        .anyMatch(a -> a.path("data").path("summary").asString("").contains("Sospetta frode"));
                yield reason + "|" + (inAudit ? "audit" : "senza audit");
            }
            case "AUDIT" -> {
                String m = newMember();
                http(HttpMethod.POST, "/v1/members/" + m + "/status", "CARE:paolo.care", Map.of("status", "BLOCKED"));
                JsonNode a = audits("MEMBER:" + m).stream()
                        .filter(x -> "TRANSITION".equals(x.path("data").path("action").asString())).reduce((p, q) -> q)
                        .orElse(mapper.createObjectNode());
                yield a.path("data").path("action").asString() + "|" + a.path("data").path("before").path("status").asString()
                        + "|" + a.path("data").path("after").path("status").asString() + "|" + a.path("lhactor").asString();
            }
            case "CONTRACT" -> {
                String m = newMember();
                http(HttpMethod.POST, "/v1/members/" + m + "/status", ADMIN, Map.of("status", "INACTIVE"));
                List<String> errors = contractErrors(last(facts(STATUS_CHANGED, m)), "member.status.changed");
                yield errors.isEmpty() ? "valido" : String.join("; ", errors);
            }
            case "UNKNOWN" -> http(HttpMethod.POST, "/v1/members/MBR-999999/status", ADMIN, Map.of("status", "BLOCKED")).outcome();
            case "NO_BODY" -> http(HttpMethod.POST, "/v1/members/" + newMember() + "/status", ADMIN, null).outcome();
            case "FILTER_SOSPESO" -> http(HttpMethod.GET, "/v1/members?status=SOSPESO", ADMIN, null).outcome();
            case "FILTER_CLOSED" -> String.valueOf(http(HttpMethod.GET, "/v1/members?status=CLOSED", ADMIN, null).status);
            case "FILTER_BLOCKED" -> {
                Resp r = http(HttpMethod.GET, "/v1/members?status=BLOCKED&size=100", ADMIN, null);
                List<String> seedIds = new ArrayList<>();
                r.body.path("items").forEach(i -> {
                    String mid = i.path("id").asString();
                    if (mid.compareTo("MBR-000012") <= 0) {
                        seedIds.add(mid);
                    }
                });
                yield r.status + ":" + String.join(",", seedIds);
            }
            default -> throw new IllegalArgumentException(scenario);
        };
        assertThat(got).as("%s: %s", id, description).isEqualTo(expected);
    }

    // ======================================================================= MRL
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gov/member-roles.csv", numLinesToSkip = 1)
    void roles(String id, String description, String endpoint, String actor, String expected) {
        // Righe AMBIGUO (celle «—» senza ●, intestazioni non canoniche) — TESTBOOK: ambiguo, vedi TB-GOV §13
        Resp r = switch (endpoint) {
            case "STATUS" -> http(HttpMethod.POST, "/v1/members/" + newMember() + "/status", actor, Map.of("status", "BLOCKED"));
            case "PATCH" -> http(HttpMethod.PATCH, "/v1/members/" + newMember(), actor, Map.of("city", "Ancona"));
            case "CREATE" -> http(HttpMethod.POST, "/v1/members", actor, Map.of("firstName", "Prova", "lastName", "Ruolo",
                    "email", email()));
            case "ATTR_PUT" -> http(HttpMethod.PUT, "/v1/attribute-definitions", actor, definitions());
            case "SEG_CREATE" -> http(HttpMethod.POST, "/v1/segments", actor, staticSegmentBody(code(), List.of()));
            case "SEG_UPDATE" -> http(HttpMethod.PUT, "/v1/segments/" + createStatic(List.of()), actor, Map.of("name", "Nuovo nome"));
            case "SEG_REFRESH" -> http(HttpMethod.POST, "/v1/segments/" + createDynamic(label()) + "/refresh", actor, null);
            case "SEG_MEMBERS" -> http(HttpMethod.PUT, "/v1/segments/" + createStatic(List.of()) + "/members", actor,
                    Map.of("memberIds", List.of()));
            case "SEG_PREVIEW" -> http(HttpMethod.POST, "/v1/segments/preview", actor, Map.of("criteria",
                    Map.of("field", "member.tier", "cmp", "eq", "value", "GOLD")));
            case "JOB_REFRESH" -> http(HttpMethod.POST, "/v1/demo/jobs/refresh-segments", actor, null);
            case "LIST" -> http(HttpMethod.GET, "/v1/members?size=1", actor, null);
            case "GET" -> http(HttpMethod.GET, "/v1/members/MBR-000001", actor, null);
            default -> throw new IllegalArgumentException(endpoint);
        };
        String got = r.ok() ? "2xx" : r.outcome();
        assertThat(got).as("%s: %s", id, description).isEqualTo(expected);
    }

    // ======================================================================= ANO
    private Rich rich;

    /** Membro «ricco» anonimizzato una volta sola: le righe F_*, K_*, FACT_*, SEARCH_* ne leggono lo stato dopo. */
    private record Rich(String id, String email, String referralCode, String referredBy, String registeredAt,
                        String channel) {
    }

    private static final List<String> PII = List.of("Ottavia", "Quercioli", "1112223", "Cremona", "1980-02-03");

    private Rich rich() {
        if (rich != null) {
            return rich;
        }
        String referrer = newMember();
        String refCode = member(referrer).path("referralCode").asString();
        String mail = "ottavia.quercioli." + seq.incrementAndGet() + "@example.org";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("firstName", "Ottavia");
        body.put("lastName", "Quercioli");
        body.put("email", mail);
        body.put("phone", "+39 333 1112223");
        body.put("city", "Cremona");
        body.put("gender", "F");
        body.put("channel", "STORE");
        body.put("referralCode", refCode);
        body.put("consents", Map.of("marketing", true, "profiling", true));
        String m = http(HttpMethod.POST, "/v1/members", ADMIN, body).expect(201).path("id").asString();
        http(HttpMethod.PATCH, "/v1/members/" + m, ADMIN, Map.of("birthDate", "1980-02-03",
                "attributes", Map.of("householdSize", 3), "labels", List.of("vip"))).expect(200);
        jdbc.sql("UPDATE member SET external_id = ?, avatar_seed = 'seed-testbook' WHERE id = ?")
                .params("CRM-TB-" + m, m).update();
        jdbc.sql("UPDATE member_projection SET balance_pts = 1234 WHERE member_id = ?").param(m).update();
        JsonNode before = member(m);
        http(HttpMethod.POST, "/v1/members/" + m + "/anonymize", ADMIN, Map.of("confirm", m)).expect(200);
        rich = new Rich(m, mail, before.path("referralCode").asString(), before.path("referredBy").asString(),
                before.path("registeredAt").asString(), before.path("channel").asString());
        return rich;
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gov/anonymize.csv", numLinesToSkip = 1)
    void anonymize(String id, String description, String scenario, String expected) {
        // Righe ANO-002, ANO-010 — TESTBOOK: ambiguo, vedi TB-GOV §13
        String got;
        if (scenario.startsWith("F_") || scenario.startsWith("K_")) {
            got = field(scenario);
        } else {
            got = switch (scenario) {
                case "CONFIRM_OK" -> anon(newMember(), ADMIN, "SELF");
                case "CONFIRM_PADDED" -> anon(newMember(), ADMIN, "PADDED");
                case "CONFIRM_LOWER" -> anon(newMember(), ADMIN, "LOWER");
                case "CONFIRM_OTHER" -> anon(newMember(), ADMIN, "MBR-000001");
                case "CONFIRM_EMPTY" -> anon(newMember(), ADMIN, "");
                case "CONFIRM_MISSING" -> http(HttpMethod.POST, "/v1/members/" + newMember() + "/anonymize", ADMIN,
                        Map.of()).outcome();
                case "NO_BODY" -> http(HttpMethod.POST, "/v1/members/" + newMember() + "/anonymize", ADMIN, null).outcome();
                case "REPEAT" -> {
                    String m = newMember();
                    anon(m, ADMIN, "SELF");
                    yield anon(m, ADMIN, "SELF");
                }
                case "UNKNOWN" -> anon("MBR-999999", ADMIN, "SELF");
                case "UNKNOWN_MISMATCH" -> anon("MBR-999999", ADMIN, "MBR-000001");
                case "FROM_INACTIVE", "FROM_BLOCKED" -> {
                    String from = scenario.substring(5);
                    String m = memberIn(from);
                    String r = anon(m, ADMIN, "SELF");
                    JsonNode fact = facts(STATUS_CHANGED, m).stream()
                            .filter(f -> "ANONYMIZED".equals(f.path("data").path("newStatus").asString())).findFirst()
                            .orElse(mapper.createObjectNode());
                    yield r + ":" + fact.path("data").path("previousStatus").asString();
                }
                case "ROLE_MARKETING", "ROLE_LEGAL", "ROLE_CARE", "ROLE_ANALYST", "ROLE_NONE" ->
                        anon(newMember(), scenario.substring(5), "SELF");
                case "SEED_012" -> anon("MBR-000012", ADMIN, "SELF");
                case "FACT_STATUS" -> {
                    JsonNode f = facts(STATUS_CHANGED, rich().id).stream()
                            .filter(x -> "ANONYMIZED".equals(x.path("data").path("newStatus").asString())).findFirst()
                            .orElse(mapper.createObjectNode());
                    yield f.path("data").path("previousStatus").asString() + "→" + f.path("data").path("newStatus").asString();
                }
                case "FACT_UPDATED" -> {
                    List<JsonNode> all = factsOf(rich().id);
                    int statusAt = indexOf(all, STATUS_CHANGED, "ANONYMIZED");
                    JsonNode upd = null;
                    for (int i = Math.max(statusAt, 0); i < all.size(); i++) {
                        if (UPDATED.equals(all.get(i).path("type").asString())) {
                            upd = all.get(i);
                        }
                    }
                    yield upd == null || statusAt < 0 ? "assente"
                            : upd.path("data").path("status").asString() + "|" + pii(upd.toString());
                }
                case "AUDIT" -> {
                    JsonNode a = audits("MEMBER:" + rich().id).stream()
                            .filter(x -> x.path("data").path("summary").asString("").contains("anonimizzato"))
                            .findFirst().orElse(null);
                    yield a == null ? "assente" : a.path("data").path("action").asString() + "|" + pii(a.toString());
                }
                case "CONTRACT" -> {
                    List<String> errors = new ArrayList<>();
                    for (JsonNode f : factsOf(rich().id)) {
                        String type = f.path("type").asString();
                        if (STATUS_CHANGED.equals(type)) {
                            errors.addAll(contractErrors(f, "member.status.changed"));
                        } else if (UPDATED.equals(type)) {
                            errors.addAll(contractErrors(f, "member.updated"));
                        }
                    }
                    yield errors.isEmpty() ? "validi" : String.join("; ", errors);
                }
                case "SEARCH_NAME" -> total("/v1/members?q=Quercioli", rich());
                case "SEARCH_EMAIL" -> total("/v1/members?q=" + rich().email, rich());
                case "SEARCH_ID" -> {
                    JsonNode r = http(HttpMethod.GET, "/v1/members?q=" + rich().id, ADMIN, null).body;
                    yield r.path("page").path("totalItems").asInt() + ":" + r.path("items").path(0).path("nickname").asString();
                }
                case "PORTAL" -> pii(http(HttpMethod.GET, "/v1/portal/members/" + rich().id, null, null).body.toString());
                case "EMAIL_REUSE" -> String.valueOf(http(HttpMethod.POST, "/v1/members", ADMIN, Map.of("firstName", "Altra",
                        "lastName", "Persona", "email", rich().email)).status);
                case "PATCH_AFTER" -> http(HttpMethod.PATCH, "/v1/members/" + rich().id, ADMIN, Map.of("city", "Lodi")).outcome();
                case "REFERRAL_AFTER" -> http(HttpMethod.POST, "/v1/members", ADMIN, Map.of("firstName", "Invitata",
                        "email", email(), "referralCode", rich().referralCode)).outcome();
                default -> throw new IllegalArgumentException(scenario);
            };
        }
        assertThat(got).as("%s: %s", id, description).isEqualTo(expected);
    }

    private String field(String scenario) {
        Rich r = rich();
        JsonNode m = member(r.id);
        Map<String, Object> row = jdbc.sql("SELECT consents::text AS consents, avatar_seed, external_id FROM member WHERE id = ?")
                .param(r.id).query().singleRow();
        return switch (scenario) {
            case "F_consents" -> String.valueOf(row.get("consents")).replace(" ", "");
            case "F_avatarSeed" -> String.valueOf(row.get("avatar_seed"));
            case "F_externalId" -> m.hasNonNull("externalId") ? m.path("externalId").asString() : String.valueOf(row.get("external_id"));
            case "F_attributes" -> m.path("attributes").toString();
            case "K_id" -> r.id.equals(m.path("id").asString()) ? "uguale" : m.path("id").asString();
            case "K_labels" -> {
                List<String> labels = new ArrayList<>();
                m.path("labels").forEach(l -> labels.add(l.asString()));
                yield "[" + String.join(",", labels) + "]";
            }
            case "K_referralCode" -> r.referralCode.equals(m.path("referralCode").asString()) ? "uguale" : m.path("referralCode").asString();
            case "K_referredBy" -> !r.referredBy.isBlank() && r.referredBy.equals(m.path("referredBy").asString())
                    ? "uguale" : m.path("referredBy").asString("null");
            case "K_registered" -> r.registeredAt.equals(m.path("registeredAt").asString()) && r.channel.equals(m.path("channel").asString())
                    ? "uguale" : m.path("registeredAt").asString() + "/" + m.path("channel").asString();
            case "K_projection" -> String.valueOf(m.path("balancePts").asLong());
            default -> {
                String f = scenario.substring(2);
                yield m.hasNonNull(f) ? m.path(f).asString() : "null";
            }
        };
    }

    private String anon(String memberId, String actor, String confirm) {
        String c = switch (confirm) {
            case "SELF" -> memberId;
            case "PADDED" -> "  " + memberId + " ";
            case "LOWER" -> memberId.toLowerCase();
            default -> confirm;
        };
        Resp r = http(HttpMethod.POST, "/v1/members/" + memberId + "/anonymize", actor, Map.of("confirm", c));
        return r.ok() ? r.status + ":" + r.body.path("status").asString() : r.outcome();
    }

    private String total(String path, Rich r) {
        return String.valueOf(http(HttpMethod.GET, path, ADMIN, null).body.path("page").path("totalItems").asInt());
    }

    private static String pii(String text) {
        for (String p : PII) {
            if (text.contains(p)) {
                return "contiene " + p;
            }
        }
        return text.contains("ottavia.quercioli") ? "contiene l'e-mail" : "senza dati personali";
    }

    // ======================================================================= ATU
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gov/attributes-in-use.csv", numLinesToSkip = 1)
    void attributes(String id, String description, String scenario, String expected) {
        // Righe ATU-006, ATU-009 — TESTBOOK: ambiguo, vedi TB-GOV §13. ATU-011: divergenza (TB-GOV §14).
        int n = seq.incrementAndGet();
        String key = "tbKey" + n;
        String m = newMember();
        String got = switch (scenario) {
            case "REMOVE_USED" -> {
                putDefs(withDef(key, "STRING", List.of())).expect(200);
                setAttr(m, key, "x");
                String out = putDefs(definitionsWithout(key)).outcome();
                cleanup(m, key);
                yield out;
            }
            case "RETYPE_USED" -> {
                putDefs(withDef(key, "STRING", List.of())).expect(200);
                setAttr(m, key, "x");
                String out = putDefs(replaceDef(key, "NUMBER", null, List.of())).outcome();
                cleanup(m, key);
                yield out;
            }
            case "REMOVE_UNUSED" -> {
                putDefs(withDef(key, "STRING", List.of())).expect(200);
                yield putDefs(definitionsWithout(key)).outcome();
            }
            case "RETYPE_UNUSED" -> {
                putDefs(withDef(key, "NUMBER", List.of())).expect(200);
                String out = putDefs(replaceDef(key, "BOOLEAN", null, List.of())).outcome();
                cleanup(null, key);
                yield out;
            }
            case "RELABEL_USED" -> {
                putDefs(withDef(key, "STRING", List.of())).expect(200);
                setAttr(m, key, "x");
                String out = putDefs(replaceDef(key, "STRING", "Nuova etichetta", List.of())).outcome();
                cleanup(m, key);
                yield out;
            }
            case "NARROW_OPTIONS" -> {
                putDefs(withDef(key, "STRING", List.of())).expect(200);
                setAttr(m, key, "TV");
                String out = putDefs(replaceDef(key, "STRING", null, List.of("APP", "WEB"))).outcome();
                cleanup(m, key);
                yield out;
            }
            case "CLEAR_THEN_REMOVE" -> {
                putDefs(withDef(key, "STRING", List.of())).expect(200);
                setAttr(m, key, "x");
                setAttr(m, key, null);
                yield putDefs(definitionsWithout(key)).outcome();
            }
            case "ANONYMIZED_ONLY" -> {
                putDefs(withDef(key, "STRING", List.of())).expect(200);
                setAttr(m, key, "x");
                http(HttpMethod.POST, "/v1/members/" + m + "/anonymize", ADMIN, Map.of("confirm", m)).expect(200);
                yield putDefs(definitionsWithout(key)).outcome();
            }
            case "TRIM_KEY" -> {
                Resp r = putDefs(withDef(" tbTrim ", "STRING", List.of()));
                boolean trimmed = definitions().stream().anyMatch(d -> "tbTrim".equals(d.get("key")));
                cleanup(null, "tbTrim");
                yield r.ok() ? r.status + ":" + (trimmed ? "tbTrim" : "non normalizzata") : r.outcome();
            }
            case "INVALID_DEF" -> putDefs(withDef("Bad Key", "STRING", List.of())).outcome();
            case "NULL_TYPE" -> putDefs(withDef(key, null, List.of())).outcome();
            case "AUDIT" -> {
                long before = audits("attribute_definition:all").size();
                putDefs(withDef(key, "STRING", List.of())).expect(200);
                List<JsonNode> after = audits("attribute_definition:all");
                cleanup(null, key);
                yield after.size() > before ? last(after).path("data").path("action").asString() : "nessun audit";
            }
            case "PATCH_WRONG_TYPE" -> {
                putDefs(withDef("tbNum", "NUMBER", List.of())).expect(200);
                Resp r = http(HttpMethod.PATCH, "/v1/members/" + m, ADMIN, Map.of("attributes", Map.of("tbNum", "tanti")));
                cleanup(null, "tbNum");
                yield r.outcome() + ":" + r.body.path("errors").path(0).path("field").asString();
            }
            case "PATCH_UNDEFINED" -> {
                Resp r = http(HttpMethod.PATCH, "/v1/members/" + m, ADMIN, Map.of("attributes", Map.of("tbNope", 1)));
                yield r.outcome() + ":" + r.body.path("errors").path(0).path("field").asString();
            }
            default -> throw new IllegalArgumentException(scenario);
        };
        assertThat(got).as("%s: %s", id, description).isEqualTo(expected);
    }

    private List<Map<String, Object>> definitions() {
        List<Map<String, Object>> out = new ArrayList<>();
        http(HttpMethod.GET, "/v1/attribute-definitions", ADMIN, null).body.forEach(d -> {
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("key", d.path("key").asString());
            def.put("label", d.path("label").asString());
            def.put("type", d.path("type").asString());
            List<String> opts = new ArrayList<>();
            d.path("options").forEach(o -> opts.add(o.asString()));
            def.put("options", opts);
            out.add(def);
        });
        return out;
    }

    private List<Map<String, Object>> withDef(String key, String type, List<String> options) {
        List<Map<String, Object>> defs = definitions();
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("key", key);
        def.put("label", "Attributo di prova");
        def.put("type", type);
        def.put("options", options);
        defs.add(def);
        return defs;
    }

    private List<Map<String, Object>> definitionsWithout(String key) {
        List<Map<String, Object>> defs = definitions();
        defs.removeIf(d -> key.equals(d.get("key")));
        return defs;
    }

    private List<Map<String, Object>> replaceDef(String key, String type, String label, List<String> options) {
        List<Map<String, Object>> defs = definitions();
        for (Map<String, Object> d : defs) {
            if (key.equals(d.get("key"))) {
                d.put("type", type);
                d.put("options", options);
                if (label != null) {
                    d.put("label", label);
                }
            }
        }
        return defs;
    }

    private Resp putDefs(List<Map<String, Object>> defs) {
        return http(HttpMethod.PUT, "/v1/attribute-definitions", "MARKETING", defs);
    }

    private void setAttr(String member, String key, Object value) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(key, value);
        http(HttpMethod.PATCH, "/v1/members/" + member, ADMIN, Map.of("attributes", attrs)).expect(200);
    }

    /** Toglie il valore dal membro e la chiave di prova dalle definizioni (resta sotto il tetto delle 30 chiavi). */
    private void cleanup(String member, String key) {
        if (member != null) {
            setAttr(member, key, null);
        }
        putDefs(definitionsWithout(key)).expect(200);
    }

    // ======================================================================= SEG
    private Map<String, String> statusSegment;

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gov/segments.csv", numLinesToSkip = 1)
    void segments(String id, String description, String scenario, String expected) {
        // Righe SEG-003, SEG-011, SEG-012, SEG-015…017, SEG-021, SEG-030 — TESTBOOK: ambiguo, vedi TB-GOV §13
        String got = switch (scenario) {
            case "PREVIEW_SEED" -> preview(Map.of("field", "member.tier", "cmp", "in", "value", List.of("GOLD", "PLATINUM")))
                    .body.path("count").asString();
            case "PREVIEW_INVALID" -> preview(Map.of("field", "member.shoeSize", "cmp", "eq", "value", 42)).outcome();
            case "PREVIEW_EMPTY" -> preview(Map.of()).outcome();
            case "PREVIEW_NO_WRITE" -> {
                long before = outboxCount();
                preview(Map.of("field", "member.tier", "cmp", "eq", "value", "GOLD")).expect(200);
                yield (outboxCount() - before) + " fatti";
            }
            case "CREATE_DYNAMIC" -> {
                String l = label();
                newMemberWithLabel(l);
                Resp r = http(HttpMethod.POST, "/v1/segments", "MARKETING", dynamicSegmentBody(code(), l));
                yield r.status + ":" + r.body.path("memberCount").asInt();
            }
            case "CREATE_ENTERED" -> {
                String l = label();
                String m = newMemberWithLabel(l);
                String code = createDynamic(l);
                yield segmentFacts(ENTERED, code).stream().anyMatch(f -> ("member:" + m).equals(f.path("subject").asString()))
                        ? "entered" : "nessun entered";
            }
            case "CODE_2" -> createCode("TB").outcome();
            case "CODE_3" -> String.valueOf(createCode("TB3").status);
            case "CODE_40" -> String.valueOf(createCode("TB-" + "X".repeat(37)).status);
            case "CODE_41" -> createCode("TB-" + "X".repeat(38)).outcome();
            case "CODE_LOWER" -> {
                Resp r = createCode("tb-lower-seg");
                yield r.ok() ? r.status + ":" + ("TB-LOWER-SEG".equals(r.body.path("code").asString()) ? "maiuscolo" : r.body.path("code").asString())
                        : r.outcome();
            }
            case "NAME_BLANK" -> {
                Map<String, Object> b = staticSegmentBody(code(), List.of());
                b.put("name", "   ");
                yield http(HttpMethod.POST, "/v1/segments", "MARKETING", b).outcome();
            }
            case "TYPE_UNKNOWN" -> {
                Map<String, Object> b = staticSegmentBody(code(), List.of());
                b.put("type", "MIXED");
                yield http(HttpMethod.POST, "/v1/segments", "MARKETING", b).outcome();
            }
            case "CODE_TAKEN" -> {
                String c = code();
                createCode(c).expect(201);
                yield createCode(c).outcome();
            }
            case "STATIC_UNKNOWN" -> http(HttpMethod.POST, "/v1/segments", "MARKETING",
                    staticSegmentBody(code(), List.of("MBR-999999"))).outcome();
            case "STATIC_ANONYMIZED" -> {
                String m = memberIn("ANONYMIZED");
                yield http(HttpMethod.POST, "/v1/segments", "MARKETING", staticSegmentBody(code(), List.of(m))).outcome();
            }
            case "MEMBERS_ON_DYNAMIC" -> http(HttpMethod.PUT, "/v1/segments/" + createDynamic(label()) + "/members",
                    "MARKETING", Map.of("memberIds", List.of())).outcome();
            case "CHANGE_TYPE" -> http(HttpMethod.PUT, "/v1/segments/" + createDynamic(label()), "MARKETING",
                    Map.of("type", "STATIC")).outcome();
            case "CHANGE_CODE" -> http(HttpMethod.PUT, "/v1/segments/" + createDynamic(label()), "MARKETING",
                    Map.of("code", code())).outcome();
            case "ARCHIVE_LEFT" -> {
                String l = label();
                String m = newMemberWithLabel(l);
                String code = createDynamic(l);
                http(HttpMethod.PUT, "/v1/segments/" + code, "MARKETING", Map.of("status", "ARCHIVED")).expect(200);
                yield segmentFacts(LEFT, code).stream().anyMatch(f -> ("member:" + m).equals(f.path("subject").asString()))
                        ? "left" : "nessun left";
            }
            case "REFRESH_ARCHIVED" -> {
                String code = createDynamic(label());
                http(HttpMethod.PUT, "/v1/segments/" + code, "MARKETING", Map.of("status", "ARCHIVED")).expect(200);
                yield http(HttpMethod.POST, "/v1/segments/" + code + "/refresh", "MARKETING", null).outcome();
            }
            case "STATUS_UNKNOWN" -> http(HttpMethod.PUT, "/v1/segments/" + createDynamic(label()), "MARKETING",
                    Map.of("status", "PAUSED")).outcome();
            case "REFRESH_TWICE" -> {
                String l = label();
                newMemberWithLabel(l);
                String code = createDynamic(l);
                http(HttpMethod.POST, "/v1/segments/" + code + "/refresh", "MARKETING", null).expect(200);
                JsonNode r = http(HttpMethod.POST, "/v1/segments/" + code + "/refresh", "MARKETING", null).expect(200);
                yield r.path("entered").asInt() + ":" + r.path("left").asInt();
            }
            case "CRITERIA_CHANGED" -> {
                String l = label();
                String m = newMemberWithLabel(l);
                String code = createDynamic(l);
                http(HttpMethod.PUT, "/v1/segments/" + code, "MARKETING", Map.of("criteria", labelCriteria(label()))).expect(200);
                yield segmentFacts(LEFT, code).stream().anyMatch(f -> ("member:" + m).equals(f.path("subject").asString()))
                        ? "left" : "nessun left";
            }
            case "STATIC_REPLACE" -> {
                String m1 = newMember();
                String m2 = newMember();
                String code = createStatic(List.of(m1));
                JsonNode r = http(HttpMethod.PUT, "/v1/segments/" + code + "/members", "MARKETING",
                        Map.of("memberIds", List.of(m2))).expect(200);
                yield r.path("entered").asInt() + ":" + r.path("left").asInt();
            }
            case "STATUS_ACTIVE", "STATUS_INACTIVE", "STATUS_BLOCKED", "STATUS_ANONYMIZED" -> {
                Map<String, String> s = statusSegment();
                Set<String> in = new java.util.HashSet<>();
                http(HttpMethod.GET, "/v1/segments/" + s.get("code") + "/members?size=50", ADMIN, null).body.path("items")
                        .forEach(i -> in.add(i.path("memberId").asString()));
                yield in.contains(s.get(scenario.substring(7))) ? "dentro" : "fuori";
            }
            case "VERSION_CONFLICT" -> {
                String code = createDynamic(label());
                int v = http(HttpMethod.GET, "/v1/segments/" + code, ADMIN, null).body.path("version").asInt();
                http(HttpMethod.PUT, "/v1/segments/" + code, "MARKETING", Map.of("version", v, "name", "Primo")).expect(200);
                yield http(HttpMethod.PUT, "/v1/segments/" + code, "MARKETING", Map.of("version", v, "name", "Secondo")).outcome();
            }
            default -> throw new IllegalArgumentException(scenario);
        };
        assertThat(got).as("%s: %s", id, description).isEqualTo(expected);
    }

    /** Un segmento dinamico su un'etichetta propria con quattro membri, uno per stato (Q-85). */
    private Map<String, String> statusSegment() {
        if (statusSegment != null) {
            return statusSegment;
        }
        String l = label();
        Map<String, String> s = new HashMap<>();
        for (String st : List.of("ACTIVE", "INACTIVE", "BLOCKED", "ANONYMIZED")) {
            String m = newMemberWithLabel(l);
            if (!"ACTIVE".equals(st)) {
                moveTo(m, st);
            }
            s.put(st, m);
        }
        s.put("code", createDynamic(l));
        statusSegment = s;
        return s;
    }

    private Resp preview(Map<String, Object> criteria) {
        return http(HttpMethod.POST, "/v1/segments/preview", "ANALYST", Map.of("criteria", criteria));
    }

    private Resp createCode(String code) {
        return http(HttpMethod.POST, "/v1/segments", "MARKETING", staticSegmentBody(code, List.of()));
    }

    private Map<String, Object> staticSegmentBody(String code, List<String> members) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("code", code);
        b.put("name", "Segmento di prova " + code);
        b.put("type", "STATIC");
        b.put("memberIds", members);
        return b;
    }

    private Map<String, Object> dynamicSegmentBody(String code, String label) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("code", code);
        b.put("name", "Segmento di prova " + code);
        b.put("type", "DYNAMIC");
        b.put("criteria", labelCriteria(label));
        return b;
    }

    private static Map<String, Object> labelCriteria(String label) {
        return Map.of("op", "all", "rules", List.of(Map.of("field", "member.labels", "cmp", "contains", "value", label)));
    }

    private String createStatic(List<String> members) {
        String c = code();
        http(HttpMethod.POST, "/v1/segments", ADMIN, staticSegmentBody(c, members)).expect(201);
        return c;
    }

    private String createDynamic(String label) {
        String c = code();
        http(HttpMethod.POST, "/v1/segments", ADMIN, dynamicSegmentBody(c, label)).expect(201);
        return c;
    }

    private List<JsonNode> segmentFacts(String type, String code) {
        return jdbc.sql("SELECT payload::text FROM outbox WHERE type = ? AND payload->'data'->>'segmentCode' = ? ORDER BY created_at")
                .params(type, code).query(String.class).list().stream().map(mapper::readTree).toList();
    }

    // ======================================================================= supporto
    record Resp(int status, JsonNode body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }

        String outcome() {
            return ok() ? String.valueOf(status) : status + ":" + body.path("code").asString("");
        }

        JsonNode expect(int expected) {
            assertThat(status).as("preparazione: " + body).isEqualTo(expected);
            return body;
        }
    }

    /**
     * {@code actor}: un ruolo ({@code ADMIN}…) con username fisso, {@code NONE}/{@code null} = intestazione assente,
     * altrimenti il valore grezzo dell'intestazione.
     */
    private Resp http(HttpMethod method, String path, String actor, Object body) {
        var spec = RestClient.create("http://localhost:" + port).method(method).uri(path);
        String header = actor == null || "NONE".equals(actor) ? null
                : actor.contains(":") ? actor : actor + ":tb." + actor.toLowerCase();
        if (header != null) {
            spec = spec.header("X-LH-Actor", header);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        } else if (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH) {
            spec = spec.contentType(MediaType.APPLICATION_JSON);
        }
        return spec.exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode json;
            try {
                json = text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text);
            } catch (RuntimeException e) {
                json = mapper.createObjectNode();
            }
            return new Resp(res.getStatusCode().value(), json);
        });
    }

    private String email() {
        return "tb.gov." + seq.incrementAndGet() + "@example.org";
    }

    private String code() {
        return "TB-SEG-" + seq.incrementAndGet();
    }

    private String label() {
        return "tb-gov-" + seq.incrementAndGet();
    }

    private String newMember() {
        return http(HttpMethod.POST, "/v1/members", ADMIN, Map.of("firstName", "Prova", "lastName", "Testbook",
                "email", email())).expect(201).path("id").asString();
    }

    private String newMemberWithLabel(String label) {
        String m = newMember();
        http(HttpMethod.PATCH, "/v1/members/" + m, ADMIN, Map.of("labels", List.of(label))).expect(200);
        return m;
    }

    private String memberIn(String status) {
        String m = newMember();
        moveTo(m, status);
        return m;
    }

    private void moveTo(String m, String status) {
        switch (status) {
            case "ACTIVE" -> {
            }
            case "ANONYMIZED" -> http(HttpMethod.POST, "/v1/members/" + m + "/anonymize", ADMIN, Map.of("confirm", m)).expect(200);
            default -> http(HttpMethod.POST, "/v1/members/" + m + "/status", ADMIN, Map.of("status", status)).expect(200);
        }
    }

    private JsonNode member(String id) {
        return http(HttpMethod.GET, "/v1/members/" + id, ADMIN, null).expect(200);
    }

    private List<JsonNode> facts(String type, String memberId) {
        return jdbc.sql("SELECT payload::text FROM outbox WHERE type = ? AND payload->>'subject' = ? ORDER BY created_at")
                .params(type, "member:" + memberId).query(String.class).list().stream().map(mapper::readTree).toList();
    }

    private List<JsonNode> factsOf(String memberId) {
        return jdbc.sql("SELECT payload::text FROM outbox WHERE topic = 'lh.facts.v1' AND payload->>'subject' = ? ORDER BY created_at")
                .param("member:" + memberId).query(String.class).list().stream().map(mapper::readTree).toList();
    }

    private List<JsonNode> audits(String subject) {
        return jdbc.sql("SELECT payload::text FROM outbox WHERE type = ? AND msg_key = ? ORDER BY created_at")
                .params(AUDIT, subject).query(String.class).list().stream().map(mapper::readTree).toList();
    }

    private long outboxCount() {
        return jdbc.sql("SELECT count(*) FROM outbox").query(Long.class).single();
    }

    private static int indexOf(List<JsonNode> events, String type, String newStatus) {
        for (int i = 0; i < events.size(); i++) {
            JsonNode e = events.get(i);
            if (type.equals(e.path("type").asString()) && newStatus.equals(e.path("data").path("newStatus").asString())) {
                return i;
            }
        }
        return -1;
    }

    private static JsonNode last(List<JsonNode> list) {
        return list.isEmpty() ? new ObjectMapper().createObjectNode() : list.get(list.size() - 1);
    }

    private List<String> contractErrors(JsonNode event, String factType) {
        List<String> errors = new ArrayList<>(validator.validate("tb-env", resource("contracts/events/envelope.schema.json"),
                event.toString()));
        errors.addAll(validator.validate("tb-" + factType,
                resource("contracts/events/fact/" + factType + ".schema.json"), event.path("data").toString()));
        return errors;
    }

    private static String resource(String path) {
        try (InputStream in = TestbookGovMemberIT.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("risorsa mancante: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
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
