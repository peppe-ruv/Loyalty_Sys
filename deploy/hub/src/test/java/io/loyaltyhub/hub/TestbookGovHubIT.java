package io.loyaltyhub.hub;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-GOV §12 — governance tra servizi nell'hub consolidato (profili {@code demo, inproc}, approvazione accesa come in
 * {@code hub.yml}): cablaggio della policy per tipo di oggetto (ENT), casella approvazioni (APQ), matrice capacità ×
 * ruolo di docs/08 §2 cella per cella (MAT), effetti dello stato del membro su accumulo, spesa, gioco e rettifiche negli
 * altri servizi (EFF), propagazione dell'anonimizzazione (ANX).
 * <p>Un solo contesto Spring. Ogni riga crea oggetti e membri propri con codici nuovi; le celle ✓ della matrice usano
 * risorse inesistenti o corpi non validi, così provano solo la guardia (esito ≠ 403) senza modificare i dati del seed.
 * Le propagazioni tra servizi si attendono con un'interrogazione ripetuta fino a 20 s, mai con un'attesa fissa.
 */
@SpringBootTest(
        classes = HubApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.name=hub")
@ActiveProfiles({"demo", "inproc"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestbookGovHubIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String ADMIN = "ADMIN";
    private static final String TRIGGER = "tbgov.lifecycle";

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger seq = new AtomicInteger();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url",
                () -> base + "&currentSchema=ingestion,member,campaign,wallet,insight,reward,gamification,engagement");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    // ======================================================================= ENT
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gov/hub-entities.csv", numLinesToSkip = 1)
    void entities(String id, String description, String entity, String scenario, String expected) {
        // Righe ACTIVATE (ENT-019, ENT-031, ENT-043) — TESTBOOK: ambiguo, vedi TB-GOV §13. ENT-046: divergenza (§14).
        Obj o = create(entity);
        String got = switch (scenario) {
            case "PUBLISH_DRAFT" -> o.move("MARKETING", "PUBLISH", null).state();
            case "SUBMIT_APPROVE_PUBLISH" -> {
                o.move("MARKETING", "SUBMIT", null).ok();
                o.move("LEGAL", "APPROVE", "conforme").ok();
                yield o.move("MARKETING", "PUBLISH", null).state();
            }
            case "MARKETING_APPROVE" -> {
                o.move("MARKETING", "SUBMIT", null).ok();
                yield o.move("MARKETING", "APPROVE", "ok").state();
            }
            case "LEGAL_PUBLISH" -> {
                o.move("MARKETING", "SUBMIT", null).ok();
                o.move("LEGAL", "APPROVE", "ok").ok();
                yield o.move("LEGAL", "PUBLISH", null).state();
            }
            case "CARE_SUBMIT" -> o.move("CARE", "SUBMIT", null).state();
            case "ANALYST_SUBMIT" -> o.move("ANALYST", "SUBMIT", null).state();
            case "REJECT_NO_COMMENT" -> {
                o.move("MARKETING", "SUBMIT", null).ok();
                yield o.move("LEGAL", "REJECT", null).state();
            }
            case "REJECT_COMMENT" -> {
                o.move("MARKETING", "SUBMIT", null).ok();
                String s = o.move("LEGAL", "REJECT", "regolamento incompleto").state();
                boolean inHistory = o.history().stream().anyMatch(h -> "REJECT".equals(h.path("action").asString())
                        && "regolamento incompleto".equals(h.path("comment").asString()));
                yield s + "|" + (inHistory ? "commento nello storico" : "commento assente");
            }
            case "ADMIN_OVERRIDE", "LEGAL_AUDIT" -> {
                o.move("MARKETING", "SUBMIT", null).ok();
                String s = o.move("ADMIN_OVERRIDE".equals(scenario) ? ADMIN : "LEGAL", "APPROVE", "ok").state();
                String summary = audits(o.code).stream().map(a -> a.path("data").path("summary").asString(""))
                        .filter(x -> x.contains("(APPROVE)")).findFirst().orElse("");
                yield s + "|" + (summary.isEmpty() ? "audit assente"
                        : summary.contains("[override ADMIN]") ? "override in audit" : "nessun override");
            }
            case "HISTORY" -> {
                if ("CONTENT".equals(entity)) {
                    o.move("MARKETING", "PUBLISH", null).ok();
                    long n = jdbc.sql("SELECT count(*) FROM approval_history WHERE entity_id = ?").param(o.id)
                            .query(Long.class).single();
                    yield n + " voce in approval_history";
                }
                o.move("MARKETING", "SUBMIT", null).ok();
                o.move("LEGAL", "APPROVE", "conforme").ok();
                List<JsonNode> h = new ArrayList<>(o.history());
                h.sort((a, b) -> a.path("createdAt").asString().compareTo(b.path("createdAt").asString()));
                List<String> actions = h.stream().map(x -> x.path("action").asString()).toList();
                boolean who = h.stream().allMatch(x -> x.path("actor").asString().contains(":tb."));
                boolean when = h.stream().allMatch(x -> !x.path("createdAt").asString().isBlank());
                boolean comment = h.stream().anyMatch(x -> "conforme".equals(x.path("comment").asString()));
                yield String.join(",", actions) + (who ? "|chi" : "|-") + (when ? "|quando" : "|-") + (comment ? "|commento" : "|-");
            }
            case "FACT" -> {
                long before = statusFacts(o);
                o.move("MARKETING", "SUBMIT", null).ok();
                o.move("LEGAL", "APPROVE", "ok").ok();
                yield (statusFacts(o) - before) + " fatti";
            }
            case "ACTIVATE" -> {
                o.move("MARKETING", "SUBMIT", null).ok();
                o.move("LEGAL", "APPROVE", "ok").ok();
                yield o.move("MARKETING", "ACTIVATE", null).state();
            }
            case "NOT_IN_QUEUE" -> {
                o.move("MARKETING", "PUBLISH", null).ok();
                yield http(HttpMethod.GET, "/v1/approvals", "LEGAL", null).body.toString().contains(o.code) ? "presente" : "assente";
            }
            default -> throw new IllegalArgumentException(scenario);
        };
        assertThat(got).as("%s: %s", id, description).isEqualTo(expected);
    }

    /** Oggetto governato creato per la riga: tipo, id, codice e percorso REST delle transizioni. */
    private final class Obj {
        final String entity;
        final String id;
        final String code;
        final String base;

        Obj(String entity, String id, String code, String base) {
            this.entity = entity;
            this.id = id;
            this.code = code;
            this.base = base;
        }

        Resp move(String actor, String action, String comment) {
            Map<String, Object> body = new HashMap<>();
            body.put("action", action);
            body.put("comment", comment);
            return http(HttpMethod.POST, base + "/" + id + "/transitions", actor, body);
        }

        List<JsonNode> history() {
            List<JsonNode> out = new ArrayList<>();
            http(HttpMethod.GET, base + "/" + id + "/approval-history", ADMIN, null).body.forEach(out::add);
            return out;
        }
    }

    private Obj create(String entity) {
        int n = seq.incrementAndGet();
        return switch (entity) {
            case "CAMPAIGN", "CAMPAIGN_LEGAL", "CAMPAIGN_100000", "CAMPAIGN_100001" -> {
                String code = "CMP-TBGOV-" + n;
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("code", code);
                b.put("name", "Testbook governance " + code);
                b.put("triggerActionTypes", List.of(TRIGGER));
                b.put("audience", Map.of("all", true, "tiers", List.of(), "segments", List.of()));
                b.put("conditions", Map.of("op", "all", "rules", List.of()));
                b.put("effects", List.of(Map.of("type", "GRANT_POINTS", "currency", "PTS", "mode", "FIXED", "value", 10)));
                b.put("limits", switch (entity) {
                    case "CAMPAIGN_100000" -> Map.of("global", Map.of("maxPoints", 100_000));
                    case "CAMPAIGN_100001" -> Map.of("global", Map.of("maxPoints", 100_001));
                    default -> Map.of();
                });
                b.put("schedule", Map.of("startAt", "2026-01-01T00:00:00Z"));
                b.put("priority", 100);
                b.put("requiresLegal", "CAMPAIGN_LEGAL".equals(entity));
                JsonNode c = http(HttpMethod.POST, "/v1/campaigns", "MARKETING", b).expect(201);
                yield new Obj(entity, c.path("id").asString(), code, "/v1/campaigns");
            }
            case "REWARD" -> {
                String code = "RWD-TBGOV-" + n;
                JsonNode r = http(HttpMethod.POST, "/v1/rewards", "MARKETING", Map.of("code", code, "name", "Premio " + code,
                        "type", "DIGITAL", "band", "F1", "category", "TEMPO", "fulfilment", "INSTANT")).expect(201);
                yield new Obj(entity, r.path("id").asString(), code, "/v1/rewards");
            }
            case "CONTEST" -> {
                String code = "IW-TBGOV-" + n;
                Instant start = Instant.now().minus(Duration.ofDays(1));
                JsonNode c = http(HttpMethod.POST, "/v1/contests", "MARKETING", Map.of("code", code, "name", "Ruota " + code,
                        "mechanic", "WHEEL", "startAt", start.toString(), "endAt", start.plus(Duration.ofDays(10)).toString(),
                        "freePlayDaily", true, "distribution", "UNIFORM",
                        "prizes", List.of(Map.of("code", "PTS-10", "name", "10 punti", "type", "POINTS", "points", 10,
                                "quantity", 5)))).expect(201);
                // Un concorso si pubblica solo con gli istanti generati (gamification §5): preparazione, non oggetto della riga.
                http(HttpMethod.POST, "/v1/contests/" + c.path("id").asString() + "/instants/generate", "MARKETING", null)
                        .expect(200);
                yield new Obj(entity, c.path("id").asString(), code, "/v1/contests");
            }
            case "CONTENT" -> {
                String code = "CNT-TBGOV-" + n;
                JsonNode c = http(HttpMethod.POST, "/v1/contents", "MARKETING", Map.of("code", code, "kind", "CARD",
                        "placement", "HOME_GRID", "title", "Testbook governance")).expect(201);
                yield new Obj(entity, c.path("id").asString(), code, "/v1/contents");
            }
            default -> throw new IllegalArgumentException(entity);
        };
    }

    private long statusFacts(Obj o) {
        String type = switch (o.entity) {
            case "REWARD" -> "io.loyaltyhub.fact.reward.status.changed";
            case "CONTEST" -> "io.loyaltyhub.fact.contest.status.changed";
            case "CONTENT" -> "io.loyaltyhub.fact.content.status.changed";
            default -> "io.loyaltyhub.fact.campaign.status.changed";
        };
        return jdbc.sql("SELECT count(*) FROM outbox WHERE type = ? AND payload::text LIKE ?")
                .params(type, "%\"" + o.code + "\"%").query(Long.class).single();
    }

    private List<JsonNode> audits(String code) {
        return jdbc.sql("SELECT payload::text FROM outbox WHERE type = 'io.loyaltyhub.audit.entry' AND payload::text LIKE ? "
                        + "ORDER BY created_at")
                .param("%" + code + "%").query(String.class).list().stream().map(mapper::readTree).toList();
    }

    // ======================================================================= APQ
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gov/hub-approvals.csv", numLinesToSkip = 1)
    void approvals(String id, String description, String scenario, String expected) {
        // Riga APQ-008 — TESTBOOK: ambiguo, vedi TB-GOV §13
        List<String> seed = List.of("CMP-BLACK-FRIDAY", "IW-NATALE", "RWD-GIFT-50");
        String got = switch (scenario) {
            case "SEED_ITEMS" -> {
                TreeSet<String> codes = new TreeSet<>();
                queue().forEach(i -> {
                    if (seed.contains(i.path("code").asString())) {
                        codes.add(i.path("code").asString());
                    }
                });
                yield String.join(",", codes);
            }
            case "REQUIRED_ROLE" -> {
                TreeSet<String> roles = new TreeSet<>();
                queue().stream().filter(i -> seed.contains(i.path("code").asString()))
                        .forEach(i -> roles.add(i.path("requiredRole").asString("null")));
                yield String.join(",", roles);
            }
            case "FORMAT" -> {
                List<String> missing = new ArrayList<>();
                JsonNode item = queue().stream().filter(i -> "RWD-GIFT-50".equals(i.path("code").asString())).findFirst()
                        .orElse(mapper.createObjectNode());
                for (String f : List.of("entityType", "id", "code", "name", "submittedBy", "submittedAt", "requiredRole", "summary")) {
                    if (!item.hasNonNull(f)) {
                        missing.add(f);
                    }
                }
                yield missing.isEmpty() ? "completo" : "mancano " + missing;
            }
            case "SUBMITTED_APPEARS" -> {
                Obj o = create("REWARD");
                o.move("MARKETING", "SUBMIT", null).ok();
                JsonNode item = queue().stream().filter(i -> o.code.equals(i.path("code").asString())).findFirst().orElse(null);
                yield item == null ? "assente" : "presente|" + item.path("submittedBy").asString();
            }
            case "APPROVED_LEAVES" -> {
                Obj o = create("REWARD");
                o.move("MARKETING", "SUBMIT", null).ok();
                o.move("LEGAL", "APPROVE", "ok").ok();
                yield queue().stream().anyMatch(i -> o.code.equals(i.path("code").asString())) ? "presente" : "assente";
            }
            case "SUBMITTED_BY" -> {
                Obj o = create("REWARD");
                String actor = "MARKETING:tb.gov" + seq.incrementAndGet();
                o.move(actor, "SUBMIT", null).ok();
                o.move("LEGAL", "APPROVE", "ok").ok();
                JsonNode item = null;
                for (JsonNode i : http(HttpMethod.GET, "/v1/approvals?submittedBy=" + actor, "LEGAL", null).body) {
                    if (o.code.equals(i.path("code").asString())) {
                        item = i;
                    }
                }
                yield item == null ? "assente" : "presente|" + item.path("status").asString();
            }
            case "POLICY" -> {
                JsonNode p = http(HttpMethod.GET, "/v1/approvals/policy", "ANALYST", null).body;
                TreeSet<String> rows = new TreeSet<>();
                p.path("rows").forEach(r -> rows.add(r.path("entityType").asString() + ":"
                        + (r.hasNonNull("approverRole") ? r.path("approverRole").asString() : "-")));
                yield p.path("enabled").asBoolean() + "|" + p.path("campaignBudgetThreshold").asLong() + "|" + String.join(",", rows);
            }
            case "CAMPAIGN_NO_RULE" -> {
                Obj o = create("CAMPAIGN");
                o.move("MARKETING", "SUBMIT", null).ok();
                JsonNode item = queue().stream().filter(i -> o.code.equals(i.path("code").asString())).findFirst()
                        .orElse(mapper.createObjectNode());
                yield item.hasNonNull("requiredRole") ? item.path("requiredRole").asString() : "null";
            }
            default -> throw new IllegalArgumentException(scenario);
        };
        assertThat(got).as("%s: %s", id, description).isEqualTo(expected);
    }

    private List<JsonNode> queue() {
        List<JsonNode> out = new ArrayList<>();
        http(HttpMethod.GET, "/v1/approvals?status=IN_REVIEW", "LEGAL", null).expect(200).forEach(out::add);
        return out;
    }

    // ======================================================================= MAT
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gov/hub-matrix.csv", numLinesToSkip = 1)
    void matrix(String id, String description, String capability, String role, String expected) {
        // Celle «—» senza ● di ruoli diversi da ANALYST — TESTBOOK: ambiguo, vedi TB-GOV §13.
        // Istogramma per CARE/ANALYST (MAT-009, MAT-010) — Q-303 DECISA: 403.
        String nope = "NOPE-TBGOV";
        String got = switch (capability) {
            case "read" -> guard(http(HttpMethod.GET, "/v1/campaigns", role, null));
            case "instants.view" -> guard(http(HttpMethod.GET, "/v1/contests/IW-AUTUNNO/instants", role, null)) + "/"
                    + guard(http(HttpMethod.GET, "/v1/contests/IW-AUTUNNO/instants/histogram", role, null));
            case "member.write" -> guard(http(HttpMethod.PATCH, "/v1/members/MBR-999999", role, Map.of("city", "Ancona")));
            case "member.anonymize" -> guard(http(HttpMethod.POST, "/v1/members/MBR-999999/anonymize", role,
                    Map.of("confirm", "MBR-999999")));
            case "points.adjust" -> guard(http(HttpMethod.POST, "/v1/wallets/MBR-999999/adjustments", role,
                    Map.of("currency", "PTS", "direction", "CREDIT", "amount", 0, "reason", "GOODWILL",
                            "note", "rettifica di prova non valida")));
            case "segment.write" -> guard(http(HttpMethod.POST, "/v1/segments", role, Map.of("code", "x", "name", "x",
                    "type", "STATIC")));
            case "object.edit" -> guard(http(HttpMethod.POST, "/v1/campaigns", role, Map.of("code", "x")));
            case "object.approve" -> {
                Obj o = create("REWARD");
                o.move(ADMIN, "SUBMIT", null).ok();
                yield guard(o.move(role, "APPROVE", "ok"));
            }
            case "content.write" -> guard(http(HttpMethod.POST, "/v1/contents", role, Map.of("code", "x")));
            case "program.config" -> guard(http(HttpMethod.PUT, "/v1/tiers/" + nope, role, Map.of()));
            case "actiontype.custom" -> guard(http(HttpMethod.POST, "/v1/event-types", role, Map.of("code", "X")));
            case "edition.close" -> guard(http(HttpMethod.POST, "/v1/editions/ED-NOPE/close?dryRun=true", role, null)) + "/"
                    + guard(http(HttpMethod.POST, "/v1/editions/ED-NOPE/close?dryRun=false", role, null));
            case "redemption.handle" -> guard(http(HttpMethod.POST, "/v1/redemptions/" + nope + "/fulfil", role, Map.of()));
            case "delivery.handle" -> guard(http(HttpMethod.POST, "/v1/plays/" + nope + "/delivery", role, Map.of()));
            case "coupon.void" -> guard(http(HttpMethod.POST, "/v1/coupons/" + nope + "/void", role, Map.of()));
            case "inbound.handle" -> guard(http(HttpMethod.POST, "/v1/inbound-events/" + nope + "/retry", role, Map.of()));
            case "webhook.write" -> guard(http(HttpMethod.POST, "/v1/webhooks", role, Map.of("code", "x")));
            case "dlq.handle" -> guard(http(HttpMethod.POST, "/v1/dlq/" + nope + "/discard", role, Map.of("note", "prova")));
            case "demo.simulate" -> guard(http(HttpMethod.POST, "/v1/demo/scenarios/" + nope + "/run", role, null));
            case "demo.admin" -> guard(http(HttpMethod.POST, "/v1/demo/contests/" + nope + "/plant-instant", role, null));
            default -> throw new IllegalArgumentException(capability);
        };
        assertThat(got).as("%s: %s", id, description).isEqualTo(expected);
    }

    /** Esito della sola guardia: {@code 403} se rifiutata per ruolo, {@code ammessa} altrimenti (5xx segnalato). */
    private static String guard(Resp r) {
        if (r.status == 403) {
            return "403";
        }
        return r.status >= 500 ? r.status + ":" + r.body.path("code").asString("") : "ammessa";
    }

    // ======================================================================= EFF
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gov/hub-effects.csv", numLinesToSkip = 1)
    void effects(String id, String description, String status, String effect, String expected) {
        String m = newMember("Effetto", "Stato");
        if ("BALANCE".equals(effect)) {
            await(() -> pts(m) >= 100 ? "ok" : "attesa", "ok");
            long before = pts(m);
            moveTo(m, status);
            String got = await(() -> memberStatusSeen(m, status), "visto") + "|"
                    + (pts(m) == before ? "invariato" : "cambiato da " + before + " a " + pts(m));
            assertThat(got).as("%s: %s", id, description).isEqualTo("visto|" + expected);
            return;
        }
        // Prima che cambi stato il membro deve essere noto a valle (bonus di benvenuto accreditato): così un rifiuto
        // misura lo stato e non un membro ancora sconosciuto.
        await(() -> pts(m) >= 100 ? "ok" : "attesa", "ok");
        moveTo(m, status);
        Supplier<String> call = switch (effect) {
            case "EARN" -> () -> {
                JsonNode r = http(HttpMethod.POST, "/v1/events", null, purchase("member:" + m)).body;
                String s = r.path("status").asString();
                return r.hasNonNull("rejectCode") ? s + ":" + r.path("rejectCode").asString() : s;
            };
            case "SPEND" -> () -> {
                Resp r = http(HttpMethod.POST, "/v1/portal/redemptions", null, Map.of("memberId", m,
                        "rewardCode", "RWD-DONATION-TREE"));
                return r.ok() ? "ammessa" : r.outcome();
            };
            case "PLAY" -> () -> {
                Resp r = http(HttpMethod.POST, "/v1/portal/contests/IW-AUTUNNO/play", null, Map.of("memberId", m));
                return r.ok() ? "ammessa" : r.outcome();
            };
            case "ADJUST" -> () -> {
                Resp r = http(HttpMethod.POST, "/v1/wallets/" + m + "/adjustments", "CARE", Map.of("currency", "PTS",
                        "direction", "CREDIT", "amount", 10, "reason", "GOODWILL", "note", "rettifica di prova testbook"));
                return r.ok() ? "ammessa" : r.outcome();
            };
            default -> throw new IllegalArgumentException(effect);
        };
        // Membro e stato arrivano agli altri servizi coi fatti member.registered e member.status.changed: si ripete finché
        // l'esito è quello atteso (i rifiuti non hanno effetti collaterali), al più 20 s.
        String got = await(call, expected);
        assertThat(got).as("%s: %s", id, description).isEqualTo(expected);
    }

    private String memberStatusSeen(String m, String status) {
        // Il wallet rifiuta la rettifica solo agli anonimizzati: si osserva lo stato dal lato ingestion (evento rifiutato).
        JsonNode r = http(HttpMethod.POST, "/v1/events", null, purchase("member:" + m)).body;
        return "ACTIVE".equals(status) == "ACCEPTED".equals(r.path("status").asString()) ? "visto" : "non ancora";
    }

    // ======================================================================= ANX
    private Anon anon;

    private record Anon(String id, String email, String redemptionId, int ledgerBefore) {
    }

    private static final String FIRST = "Zaccaria";

    private Anon anon() {
        if (anon != null) {
            return anon;
        }
        String mail = "zaccaria.pellegrinotti." + seq.incrementAndGet() + "@example.org";
        String m = http(HttpMethod.POST, "/v1/members", ADMIN, Map.of("firstName", FIRST, "lastName", "Pellegrinotti",
                "email", mail, "phone", "+39 347 5550001", "city", "Frascati")).expect(201).path("id").asString();
        await(() -> pts(m) >= 100 ? "ok" : "attesa", "ok");
        long before = pts(m);
        assertThat(http(HttpMethod.POST, "/v1/events", null, purchase("email:" + mail)).body.path("status").asString())
                .isEqualTo("ACCEPTED");
        await(() -> pts(m) > before ? "ok" : "attesa", "ok");
        String redemption = http(HttpMethod.POST, "/v1/portal/redemptions", null, Map.of("memberId", m,
                "rewardCode", "RWD-BORRACCIA", "shipping", Map.of("name", FIRST + " Pellegrinotti", "street", "Via dei Test 9",
                        "zip", "00044", "city", "Frascati"))).expect(202).path("redemptionId").asString();
        await(() -> "PENDING".equals(http(HttpMethod.GET, "/v1/redemptions/" + redemption, ADMIN, null).body
                .path("status").asString()) ? "attesa" : "ok", "ok");
        await(() -> http(HttpMethod.GET, "/v1/messages?size=100&memberId=" + m, ADMIN, null).body.toString()
                .contains(FIRST) ? "ok" : "attesa", "ok");
        int ledger = http(HttpMethod.GET, "/v1/wallets/" + m + "/ledger?limit=100", ADMIN, null).body.size();
        http(HttpMethod.POST, "/v1/members/" + m + "/anonymize", ADMIN, Map.of("confirm", m)).expect(200);
        anon = new Anon(m, mail, redemption, ledger);
        return anon;
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gov/hub-anonymization.csv", numLinesToSkip = 1)
    void anonymization(String id, String description, String scenario, String expected) {
        Anon a = anon();
        Supplier<String> probe = switch (scenario) {
            case "LEDGER" -> () -> http(HttpMethod.GET, "/v1/wallets/" + a.id + "/ledger?limit=100", ADMIN, null).body.size()
                    == a.ledgerBefore ? "uguale" : "cambiato";
            case "EMAIL_EVENT" -> () -> http(HttpMethod.POST, "/v1/events", null, purchase("email:" + a.email)).body
                    .path("status").asString();
            case "SHIPPING" -> () -> http(HttpMethod.GET, "/v1/redemptions/" + a.redemptionId, ADMIN, null).body
                    .hasNonNull("shipping") ? "presente" : "cancellato";
            case "MESSAGES" -> () -> {
                String msgs = http(HttpMethod.GET, "/v1/messages?size=100&memberId=" + a.id, ADMIN, null).body.toString();
                return !msgs.contains(FIRST) && msgs.contains("Membro anonimo") ? "segnaposto" : "nome ancora presente";
            };
            case "EVENT_STORE" -> () -> http(HttpMethod.GET, "/v1/events?limit=500&q=" + FIRST, ADMIN, null).body
                    .path("count").asString();
            default -> throw new IllegalArgumentException(scenario);
        };
        assertThat(await(probe, expected)).as("%s: %s", id, description).isEqualTo(expected);
    }

    // ======================================================================= supporto
    record Resp(int status, JsonNode body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }

        String outcome() {
            return ok() ? String.valueOf(status) : status + ":" + body.path("code").asString("");
        }

        /** Stato risultante di una transizione, oppure {@code <http>:<code>}. */
        String state() {
            return ok() ? body.path("status").asString() : outcome();
        }

        JsonNode expect(int expected) {
            assertThat(status).as("preparazione: " + body).isEqualTo(expected);
            return body;
        }
    }

    private Resp http(HttpMethod method, String path, String actor, Object body) {
        var spec = RestClient.create("http://localhost:" + port).method(method).uri(path);
        String header = actor == null || "NONE".equals(actor) ? null
                : actor.contains(":") ? actor : actor + ":tb." + actor.toLowerCase();
        if (header != null) {
            spec = spec.header("X-LH-Actor", header);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
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

    private String newMember(String first, String last) {
        return http(HttpMethod.POST, "/v1/members", ADMIN, Map.of("firstName", first, "lastName", last,
                "email", "tb.gov.hub." + seq.incrementAndGet() + "@example.org")).expect(201).path("id").asString();
    }

    private void moveTo(String m, String status) {
        switch (status) {
            case "ACTIVE" -> {
            }
            case "ANONYMIZED" -> http(HttpMethod.POST, "/v1/members/" + m + "/anonymize", ADMIN, Map.of("confirm", m)).expect(200);
            default -> http(HttpMethod.POST, "/v1/members/" + m + "/status", ADMIN, Map.of("status", status)).expect(200);
        }
    }

    private Map<String, Object> purchase(String subject) {
        return Map.of("specversion", "1.0", "id", "tbgov-" + seq.incrementAndGet() + "-" + System.nanoTime(),
                "source", "urn:loyaltyhub:source:ecommerce", "type", "purchase.completed", "subject", subject,
                "time", Instant.now().toString(),
                "data", Map.of("orderId", "ORD-TBGOV-" + seq.incrementAndGet(), "amount", 20, "currency", "EUR", "channel", "ONLINE"));
    }

    private long pts(String memberId) {
        JsonNode w = http(HttpMethod.GET, "/v1/portal/wallets/" + memberId, null, null).body;
        return w.path("balances").path("PTS").path("active").asLong();
    }

    /** Ripete {@code probe} finché restituisce {@code expected} o passano 20 s; restituisce l'ultimo esito. */
    private static String await(Supplier<String> probe, String expected) {
        long deadline = System.currentTimeMillis() + 20_000;
        String last = probe.get();
        while (!expected.equals(last) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            }
            last = probe.get();
        }
        return last;
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
