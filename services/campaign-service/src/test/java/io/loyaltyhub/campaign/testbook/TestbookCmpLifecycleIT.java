package io.loyaltyhub.campaign.testbook;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.loyaltyhub.campaign.testbook.CmpItSupport.ADMIN;
import static io.loyaltyhub.campaign.testbook.CmpItSupport.LEGAL;
import static io.loyaltyhub.campaign.testbook.CmpItSupport.MARKETING;
import static io.loyaltyhub.campaign.testbook.CmpItSupport.Resp;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-CMP §9–§11 (docs/testbook/TB-CMP-campagne.md): ciclo di vita delle campagne via API (docs/03 §3.6, docs/06 §7,
 * docs/08 §2–§3.3): stato × azione, ruoli, policy di approvazione (requiresLegal, budget 99 999 / 100 000 / 100 001),
 * campi bloccati su LIVE, versioni e duplica (M7.6), creazione. Ogni riga crea la propria campagna (code = ID riga):
 * nessuna dipendenza dallo stato mutabile del seed, che è solo letto (CMP-BLACK-FRIDAY, CMP-IW-PRIZE-*).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestbookCmpLifecycleIT {

    private static final EmbeddedPostgres PG = CmpItSupport.startPg();
    private static final String TRIGGER = "tbcmp.lifecycle";

    @Value("${local.server.port}")
    private int port;

    private CmpItSupport it;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=campaign");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @BeforeAll
    void setUp() {
        it = new CmpItSupport(port);
    }

    @AfterAll
    void tearDown() throws Exception {
        it.close();
        PG.close();
    }

    /** TB-CMP-LC: ogni stato × ogni azione con il ruolo autorizzato, policy senza approvazione (docs/03 §3.6). */
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/cmp/lifecycle.csv", numLinesToSkip = 1, delimiter = '|', quoteCharacter = '`')
    void stateByAction(String id, String description, String from, String action, String actor, String comment,
                       int http, String to) {
        String cid = it.create(id, TRIGGER, null).path("id").asString();
        it.toState(cid, from);

        Resp r = it.transition(cid, actor, action, comment, http);

        if (http == 409) {
            assertThat(r.body().path("type").asString()).as(id + " problema RFC 9457")
                    .isEqualTo("urn:loyaltyhub:problem:conflict");
        } else {
            assertThat(r.body().path("status").asString()).as(id + " stato nella risposta").isEqualTo(to);
        }
        assertThat(it.state(cid)).as(id + " stato dopo la transizione").isEqualTo(to);
    }

    /**
     * TB-CMP-LCX: azioni non valide, commento del rifiuto, campagna di sistema, path per code, storico.
     * TESTBOOK: ambiguo, vedi TB-CMP-LCX-001, LCX-002 (codice d'errore di un'azione sconosciuta), LCX-003 (ACTIVATE),
     * LCX-004 (azione minuscola): docs/03 §3.6 e docs/06 §7 non li trattano; si asserisce il comportamento attuale.
     */
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/cmp/lifecycle-extra.csv", numLinesToSkip = 1, delimiter = '|', quoteCharacter = '`')
    void lifecycleExtra(String id, String description, String scenario, int http, String to) {
        switch (scenario) {
            case "UNKNOWN_ACTION", "MISSING_ACTION", "ACTIVATE", "LOWERCASE" -> {
                String cid = it.create(id, TRIGGER, null).path("id").asString();
                String action = switch (scenario) {
                    case "UNKNOWN_ACTION" -> "DELETE";
                    case "MISSING_ACTION" -> null;
                    case "ACTIVATE" -> "ACTIVATE";
                    default -> "publish";
                };
                Map<String, Object> req = new HashMap<>();
                req.put("action", action);
                it.expect("POST", "/v1/campaigns/" + cid + "/transitions", MARKETING, req, http);
                assertThat(it.state(cid)).as(id).isEqualTo(to);
            }
            case "REJECT_NO_COMMENT", "REJECT_BLANK_COMMENT" -> {
                String cid = it.create(id, TRIGGER, null).path("id").asString();
                it.toState(cid, "IN_REVIEW");
                Resp r = it.transition(cid, LEGAL, "REJECT", "REJECT_NO_COMMENT".equals(scenario) ? null : "   ", http);
                assertThat(r.body().path("type").asString()).as(id).isEqualTo("urn:loyaltyhub:problem:validation");
                assertThat(it.state(cid)).as(id).isEqualTo(to);
            }
            case "UNKNOWN_CAMPAIGN" -> it.transition("01NONESISTE00000000000000", MARKETING, "SUBMIT", null, http);
            case "SYSTEM_ARCHIVE" -> {
                // Campagna di sistema del seed (F-CMP-12): si termina (ammesso) e poi non si archivia.
                String cid = it.idOf("CMP-IW-PRIZE-COUPON");
                if (!"ENDED".equals(it.state(cid))) {
                    it.transition(cid, ADMIN, "END", "caso " + id, 200);
                }
                it.transition(cid, ADMIN, "ARCHIVE", "caso " + id, http);
                assertThat(it.state(cid)).as(id).isEqualTo(to);
            }
            case "BY_CODE" -> {
                it.create(id, TRIGGER, null);
                it.transition(id, MARKETING, "SUBMIT", null, http);
                assertThat(it.state(it.idOf(id))).as(id).isEqualTo(to);
            }
            case "HISTORY" -> {
                String cid = it.create(id, TRIGGER, null).path("id").asString();
                it.transition(cid, MARKETING, "SUBMIT", "Pronta per LEGAL", http);
                JsonNode h = it.expect("GET", "/v1/campaigns/" + cid + "/approval-history", "ANALYST:sara", null, 200).body();
                assertThat(h).as(id).hasSize(1);
                assertThat(h.get(0).path("actor").asString()).as(id + " chi").isEqualTo(MARKETING);
                assertThat(h.get(0).path("comment").asString()).as(id + " commento").isEqualTo("Pronta per LEGAL");
                assertThat(h.get(0).path("createdAt").asString()).as(id + " quando").isNotBlank();
                assertThat(h.get(0).path("fromStatus").asString() + ">" + h.get(0).path("toStatus").asString())
                        .as(id).isEqualTo("DRAFT>" + to);
            }
            default -> throw new IllegalArgumentException(scenario);
        }
    }

    /**
     * TB-CMP-ROL: operazione × ruolo (docs/06 §3, docs/08 §2).
     * TESTBOOK: ambiguo, vedi TB-CMP-ROL-003, ROL-004, ROL-010, ROL-011, ROL-017, ROL-018, ROL-024, ROL-025: la matrice
     * nega object.edit a LEGAL e CARE ma senza ● (il backend deve rifiutare solo ANALYST); si asserisce il comportamento
     * attuale (Q-247): con la guardia ADMIN/MARKETING anche su POST /v1/campaigns ogni scrittura di LEGAL e CARE è 403.
     */
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/cmp/roles.csv", numLinesToSkip = 1, delimiter = '|', quoteCharacter = '`')
    void roles(String id, String description, String operation, String actor, int http) {
        switch (operation) {
            case "CREATE" -> it.expect("POST", "/v1/campaigns", actor, it.body(id, TRIGGER, null), http);
            case "UPDATE" -> {
                String cid = it.create(id, TRIGGER, null).path("id").asString();
                it.expect("PUT", "/v1/campaigns/" + cid, actor, Map.of("name", "Rinominata"), http);
            }
            case "DUPLICATE" -> {
                String cid = it.create(id, TRIGGER, null).path("id").asString();
                it.expect("POST", "/v1/campaigns/" + cid + "/duplicate", actor, null, http);
            }
            case "SUBMIT" -> {
                String cid = it.create(id, TRIGGER, null).path("id").asString();
                it.transition(cid, actor, "SUBMIT", null, http);
            }
            case "APPROVE", "REJECT", "APPROVE_NOPOLICY" -> {
                Map<String, Object> budget = "APPROVE_NOPOLICY".equals(operation) ? null
                        : Map.of("limits", Map.of("global", Map.of("maxPoints", 200_000)));
                String cid = it.create(id, TRIGGER, budget).path("id").asString();
                it.transition(cid, MARKETING, "SUBMIT", null, 200);
                it.transition(cid, actor, "REJECT".equals(operation) ? "REJECT" : "APPROVE", "Decisione", http);
            }
            default -> throw new IllegalArgumentException(operation);
        }
    }

    /** TB-CMP-POL: policy delle campagne (docs/06 §7): requiresLegal o budget &gt; 100 000 punti ⇒ approvazione LEGAL. */
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/cmp/policy.csv", numLinesToSkip = 1, delimiter = '|', quoteCharacter = '`')
    void policy(String id, String description, String scenario, Long budget, int http, String to) {
        Map<String, Object> limits = budget == null ? null : Map.of("limits", Map.of("global", Map.of("maxPoints", budget)));
        String cid;
        switch (scenario) {
            case "PUBLISH", "ADMIN_PUBLISH" -> {
                cid = it.create(id, TRIGGER, limits).path("id").asString();
                it.transition(cid, "ADMIN_PUBLISH".equals(scenario) ? ADMIN : MARKETING, "PUBLISH", null, http);
            }
            case "FULL_PATH" -> {
                cid = it.create(id, TRIGGER, limits).path("id").asString();
                it.transition(cid, MARKETING, "SUBMIT", null, 200);
                it.transition(cid, LEGAL, "APPROVE", "Budget verificato", 200);
                it.transition(cid, MARKETING, "PUBLISH", null, http);
            }
            case "REQUIRES_LEGAL_COPY" -> {
                // CMP-BLACK-FRIDAY è requiresLegal nel seed (docs/10 §4): la copia conserva il flag, il budget si toglie.
                JsonNode copy = it.expect("POST", "/v1/campaigns/" + it.idOf("CMP-BLACK-FRIDAY") + "/duplicate", MARKETING, null, 201).body();
                cid = copy.path("id").asString();
                it.expect("PUT", "/v1/campaigns/" + cid, MARKETING, Map.of("limits", Map.of()), 200);
                it.transition(cid, MARKETING, "PUBLISH", null, http);
            }
            case "REQUIRES_LEGAL_CREATE" -> {
                // BO-06 sezione 1: requiresLegal fa parte dei campi dell'editor, quindi del corpo di creazione.
                cid = it.create(id, TRIGGER, Map.of("requiresLegal", true)).path("id").asString();
                it.transition(cid, MARKETING, "PUBLISH", null, http);
            }
            case "LOWERED" -> {
                cid = it.create(id, TRIGGER, limits).path("id").asString();
                it.expect("PUT", "/v1/campaigns/" + cid, MARKETING, Map.of("limits", Map.of("global", Map.of("maxPoints", 99_999))), 200);
                it.transition(cid, MARKETING, "PUBLISH", null, http);
            }
            case "MAX_MATCHES" -> {
                cid = it.create(id, TRIGGER, Map.of("limits", Map.of("global", Map.of("maxMatches", 200_000)))).path("id").asString();
                it.transition(cid, MARKETING, "PUBLISH", null, http);
            }
            case "QUEUE" -> {
                cid = it.create(id, TRIGGER, limits).path("id").asString();
                it.transition(cid, MARKETING, "SUBMIT", null, http);
                JsonNode queue = it.expect("GET", "/v1/approvals", LEGAL, null, 200).body();
                JsonNode item = null;
                for (JsonNode q : queue) {
                    if (id.equals(q.path("code").asString())) {
                        item = q;
                    }
                }
                assertThat(item).as(id + " nella coda").isNotNull();
                assertThat(item.path("requiredRole").asString()).as(id + " ruolo richiesto").isEqualTo("LEGAL");
                assertThat(item.path("submittedBy").asString()).as(id + " inviata da").isEqualTo(MARKETING);
            }
            default -> throw new IllegalArgumentException(scenario);
        }
        assertThat(it.state(cid)).as(id + " stato").isEqualTo(to);
    }

    /**
     * TB-CMP-EDT: campi sicuri e bloccati per stato (docs/03 §3.6, Q-51).
     * TESTBOOK: ambiguo, vedi TB-CMP-EDT-006 (memberDescription è "descrizione"?): si asserisce il comportamento attuale.
     */
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/cmp/edit.csv", numLinesToSkip = 1, delimiter = '|', quoteCharacter = '`')
    void edit(String id, String description, String state, String body, int http, String code) {
        if ("NONE".equals(state)) {
            it.expect("PUT", "/v1/campaigns/01NONESISTE00000000000000", MARKETING, it.mapper.readTree(body), http);
            return;
        }
        JsonNode created = it.create(id, TRIGGER, null);
        String cid = created.path("id").asString();
        it.toState(cid, state);
        JsonNode before = it.get(cid);
        Object payload;
        if ("FULL_BODY".equals(body)) {
            Map<String, Object> full = it.body(id, TRIGGER, null);
            full.put("name", "Nome nuovo con corpo completo");
            payload = full;
        } else {
            payload = it.mapper.readTree(body);
        }

        Resp r = it.expect("PUT", "/v1/campaigns/" + cid, MARKETING, payload, http);

        JsonNode after = it.get(cid);
        if (code != null) {
            assertThat(r.code()).as(id + " code").isEqualTo(code);
        }
        if (http == 200) {
            assertThat(after.path("status").asString()).as(id + " lo stato non cambia").isEqualTo(state);
            if (!"FULL_BODY".equals(body)) {
                it.mapper.readTree(body).properties().forEach(e ->
                        assertThat(after.get(e.getKey())).as(id + " campo " + e.getKey()).isEqualTo(e.getValue()));
            }
        } else {
            assertThat(after.path("version").asLong()).as(id + " nessuna modifica salvata")
                    .isEqualTo(before.path("version").asLong());
            assertThat(after.path("effects")).as(id + " effetti invariati").isEqualTo(before.path("effects"));
            assertThat(after.path("name")).as(id + " nome invariato").isEqualTo(before.path("name"));
        }
    }

    /**
     * TB-CMP-VER / TB-CMP-DUP / TB-CMP-CRT: versioni (Q-112), duplica (F-CMP-13), creazione e lettura.
     * TESTBOOK: ambiguo, vedi TB-CMP-VER-003 (PUT senza versione), TB-CMP-DUP-006 (flag system della copia): si asserisce
     * il comportamento attuale.
     */
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/cmp/version-duplicate.csv", numLinesToSkip = 1, delimiter = '|', quoteCharacter = '`')
    void versionDuplicateCreate(String id, String description, String scenario, String http, String code) {
        int status = "4xx".equals(http) ? -1 : Integer.parseInt(http);
        switch (scenario) {
            case "CURRENT", "STALE", "ABSENT", "FUTURE", "AFTER_TRANSITION", "LIVE_CURRENT" -> {
                JsonNode c = it.create(id, TRIGGER, null);
                String cid = c.path("id").asString();
                if ("LIVE_CURRENT".equals(scenario)) {
                    it.toState(cid, "LIVE");
                }
                long v = it.get(cid).path("version").asLong();
                Map<String, Object> req = new HashMap<>(Map.of("name", "Salvataggio " + id));
                switch (scenario) {
                    case "CURRENT", "LIVE_CURRENT" -> req.put("version", v);
                    case "STALE" -> {
                        it.expect("PUT", "/v1/campaigns/" + cid, "MARKETING:giulia", Map.of("name", "Prima", "version", v), 200);
                        req.put("version", v);
                    }
                    case "FUTURE" -> req.put("version", v + 5);
                    case "AFTER_TRANSITION" -> {
                        it.transition(cid, MARKETING, "SUBMIT", null, 200);
                        req.put("version", v);
                    }
                    default -> {
                    }
                }
                Resp r = it.expect("PUT", "/v1/campaigns/" + cid, MARKETING, req, status);
                if (status == 200) {
                    assertThat(r.body().path("version").asLong()).as(id + " versione incrementata").isGreaterThan(v);
                    assertThat(it.get(cid).path("name").asString()).as(id).isEqualTo("Salvataggio " + id);
                } else {
                    assertThat(r.code()).as(id).isEqualTo(code);
                    assertThat(it.get(cid).path("name").asString()).as(id + " non sovrascritta").isNotEqualTo("Salvataggio " + id);
                }
            }
            case "FIRST", "SECOND", "FROM_LIVE", "RULES", "FROM_ARCHIVED", "EDIT_COPY" -> {
                Map<String, Object> rich = "RULES".equals(scenario) ? Map.of(
                        "audience", Map.of("all", false, "tiers", List.of("GOLD"), "segments", List.of("SEG-X")),
                        "conditions", Map.of("op", "all", "rules", List.of(Map.of("field", "data.amount", "cmp", "gte", "value", 5))),
                        "limits", Map.of("perMember", List.of(Map.of("max", 2, "period", "DAY"))),
                        "schedule", Map.of("startAt", "2026-02-01T00:00:00Z", "endAt", "2026-12-31T23:59:59Z", "daysOfWeek", List.of("SAT")),
                        "priority", 175, "exclusiveGroup", "G-TB", "labels", List.of("PROMO"), "visibleInPortal", true) : null;
                JsonNode c = it.create(id, TRIGGER, rich);
                String cid = c.path("id").asString();
                if ("FROM_LIVE".equals(scenario) || "EDIT_COPY".equals(scenario)) {
                    it.toState(cid, "LIVE");
                }
                if ("FROM_ARCHIVED".equals(scenario)) {
                    it.toState(cid, "ARCHIVED");
                }
                if ("SECOND".equals(scenario)) {
                    it.expect("POST", "/v1/campaigns/" + cid + "/duplicate", MARKETING, null, 201);
                }
                JsonNode copy = it.expect("POST", "/v1/campaigns/" + cid + "/duplicate", MARKETING, null, status).body();
                assertThat(copy.path("code").asString()).as(id + " code").isEqualTo(id + ("SECOND".equals(scenario) ? "-COPY-2" : "-COPY-1"));
                assertThat(copy.path("status").asString()).as(id + " stato della copia").isEqualTo("DRAFT");
                assertThat(copy.path("id").asString()).as(id + " id nuovo").isNotEqualTo(cid);
                JsonNode original = it.get(cid);
                for (String f : List.of("triggerActionTypes", "audience", "conditions", "effects", "limits", "schedule",
                        "priority", "exclusiveGroup", "labels", "visibleInPortal")) {
                    assertThat(copy.get(f)).as(id + " campo copiato " + f).isEqualTo(original.get(f));
                }
                if ("FROM_LIVE".equals(scenario)) {
                    assertThat(original.path("status").asString()).as(id + " originale").isEqualTo("LIVE");
                }
                if ("EDIT_COPY".equals(scenario)) {
                    it.expect("PUT", "/v1/campaigns/" + copy.path("id").asString(), MARKETING,
                            Map.of("effects", List.of(Map.of("type", "GRANT_POINTS", "currency", "PTS", "mode", "FIXED", "value", 77))), 200);
                    assertThat(it.get(cid).path("effects").get(0).path("value").asInt()).as(id + " originale intatto").isEqualTo(10);
                }
            }
            case "FROM_SYSTEM" -> {
                JsonNode copy = it.expect("POST", "/v1/campaigns/" + it.idOf("CMP-IW-PRIZE-POINTS") + "/duplicate", MARKETING, null, status).body();
                assertThat(copy.path("status").asString()).as(id).isEqualTo("DRAFT");
                assertThat(copy.path("system").asBoolean()).as(id + " la copia non è di sistema").isFalse();
            }
            case "LONG_CODE" -> {
                String longCode = "TB-CMP-DUP-007-" + "X".repeat(25); // 40 caratteri, al limite del formato
                assertThat(longCode).hasSize(40);
                String cid = it.create(longCode, TRIGGER, null).path("id").asString();
                JsonNode copy = it.expect("POST", "/v1/campaigns/" + cid + "/duplicate", MARKETING, null, status).body();
                assertThat(copy.path("code").asString()).as(id + " formato del code (docs/06 §2)").matches("^[A-Z][A-Z0-9-]{2,39}$");
            }
            case "UNKNOWN" -> it.expect("POST", "/v1/campaigns/01NONESISTE00000000000000/duplicate", MARKETING, null, status);
            case "VALID" -> {
                JsonNode c = it.expect("POST", "/v1/campaigns", MARKETING, it.body(id, TRIGGER, null), status).body();
                assertThat(c.path("status").asString()).as(id).isEqualTo("DRAFT");
                assertThat(c.path("id").asString()).as(id).isNotBlank();
                assertThat(c.has("version")).as(id + " versione").isTrue();
            }
            case "DUPLICATE_CODE" -> {
                it.create(id, TRIGGER, null);
                it.expect("POST", "/v1/campaigns", MARKETING, it.body(id, TRIGGER, null), status);
            }
            case "BAD_CODE", "MISSING_CODE" -> {
                Map<String, Object> b = it.body("BAD_CODE".equals(scenario) ? "tb cmp minuscolo" : null, TRIGGER, null);
                if ("MISSING_CODE".equals(scenario)) {
                    b.remove("code");
                }
                Resp r = it.send("POST", "/v1/campaigns", MARKETING, b);
                assertThat(r.status()).as(id + " rifiutata con 4xx → " + r.body()).isBetween(400, 499);
            }
            case "INVALID" -> it.expect("POST", "/v1/campaigns", MARKETING,
                    it.body(id, TRIGGER, Map.of("effects", List.of())), status);
            case "GET_BY_CODE" -> {
                it.create(id, TRIGGER, null);
                assertThat(it.expect("GET", "/v1/campaigns/" + id, "ANALYST:sara", null, status).body().path("code").asString())
                        .as(id).isEqualTo(id);
            }
            case "GET_BY_ID" -> {
                String cid = it.create(id, TRIGGER, null).path("id").asString();
                assertThat(it.expect("GET", "/v1/campaigns/" + cid, "ANALYST:sara", null, status).body().path("code").asString())
                        .as(id).isEqualTo(id);
            }
            case "GET_UNKNOWN" -> it.expect("GET", "/v1/campaigns/01NONESISTE00000000000000", "ANALYST:sara", null, status);
            default -> throw new IllegalArgumentException(scenario);
        }
    }
}
