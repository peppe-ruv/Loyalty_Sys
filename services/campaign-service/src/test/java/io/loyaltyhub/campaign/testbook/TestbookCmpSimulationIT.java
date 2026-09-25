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
import tools.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.loyaltyhub.campaign.testbook.CmpItSupport.MARKETING;
import static io.loyaltyhub.campaign.testbook.CmpItSupport.Resp;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-CMP §12 (docs/testbook/TB-CMP-campagne.md): simulazione ({@code POST /v1/campaigns/simulate}, F-CMP-08) contro
 * valutazione reale via Kafka (docs/03 §3.5: la simulazione esegue i passi 1–4 senza consumare i limiti e senza
 * scrivere). Ogni riga usa un tipo azione custom proprio ({@code tbcmp.*}), campagne e membri freschi (registrati col
 * fatto {@code member.registered}); le attese sono polling con timeout.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestbookCmpSimulationIT {

    private static final EmbeddedPostgres PG = CmpItSupport.startPg();
    private static final String TUESDAY = "2026-09-15T09:00:00Z";

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

    /** TB-CMP-EQV: stessa azione, stesso membro, stesse campagne ⇒ simulazione e valutazione reale danno gli stessi risultati. */
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/cmp/equivalence.csv", numLinesToSkip = 1, delimiter = '|', quoteCharacter = '`')
    void simulationEqualsRealEvaluation(String id, String description, String campaigns, String tier, String data,
                                        String time, String expected) {
        String trigger = trigger(id);
        String member = member(id);
        int i = 0;
        for (JsonNode overrides : it.mapper.readTree(campaigns)) {
            live(id + "-" + (++i), trigger, it.mapper.convertValue(overrides, Map.class));
        }
        if (tier != null) {
            it.registerMember(member, tier);
        }
        JsonNode payload = it.mapper.readTree(data);

        JsonNode sim = simulate(trigger, time, payload, member, null, null).body();
        it.action(id + "-A", trigger, member, time, payload);
        JsonNode real = it.awaitEvaluation(id + "-A");

        assertThat(sim.path("outcome").asString()).as(id + " esito simulato").isEqualTo(expected);
        assertThat(it.awaitOutcome(member, id + "-A")).as(id + " esito reale").isEqualTo(expected);
        assertThat(sim.path("results")).as(id + " stessi risultati per campagna").isEqualTo(real);
    }

    /**
     * TB-CMP-SIM: semantica della simulazione e della valutazione reale (limiti consumati, stati, API).
     * TB-CMP-SIM-015 (cooldown) e SIM-021 (tetto punti per membro) sono regole della specifica senza codice: le righe
     * falliscono finché non sono implementate.
     */
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/cmp/simulation.csv", numLinesToSkip = 1, delimiter = '|', quoteCharacter = '`')
    void simulation(String id, String description, String scenario) {
        String trigger = trigger(id);
        String member = member(id);
        String code = id + "-1";
        switch (scenario) {
            case "NO_CONSUME" -> {
                live(code, trigger, limitOnce());
                it.registerMember(member, "SILVER");
                assertThat(simReason(trigger, member, code)).as(id + " prima simulazione").isEqualTo("MATCHED");
                assertThat(simReason(trigger, member, code)).as(id + " seconda simulazione").isEqualTo("MATCHED");
                assertThat(realReason(id + "-A", trigger, member, TUESDAY, code)).as(id + " azione reale").isEqualTo("MATCHED");
            }
            case "LIMIT_AFTER_REAL" -> {
                live(code, trigger, limitOnce());
                it.registerMember(member, "SILVER");
                assertThat(realReason(id + "-A", trigger, member, TUESDAY, code)).as(id + " prima azione reale").isEqualTo("MATCHED");
                assertThat(simReason(trigger, member, code)).as(id + " simulazione dopo il match").isEqualTo("LIMIT");
                assertThat(realReason(id + "-B", trigger, member, "2026-09-16T09:00:00Z", code)).as(id + " seconda azione reale")
                        .isEqualTo("LIMIT");
            }
            case "NO_LOG" -> {
                live(code, trigger, null);
                it.registerMember(member, "SILVER");
                simReason(trigger, member, code);
                assertThat(it.expect("GET", "/v1/evaluations?memberId=" + member, "ANALYST:sara", null, 200).body())
                        .as(id + " nessuna riga di registro").isEmpty();
            }
            case "DRAFT_BY_ID" -> {
                String cid = it.create(code, trigger, null).path("id").asString();
                it.registerMember(member, "SILVER");
                JsonNode sim = simulate(trigger, TUESDAY, null, member, List.of(cid), null).body();
                assertThat(reasonIn(sim.path("results"), code)).as(id).isEqualTo("MATCHED");
            }
            case "DRAFT_IGNORED" -> {
                it.create(code, trigger, null);
                it.registerMember(member, "SILVER");
                JsonNode sim = simulate(trigger, TUESDAY, null, member, null, null).body();
                assertThat(reasonIn(sim.path("results"), code)).as(id + " bozza non candidata").isEqualTo("ABSENT");
            }
            case "REAL_DRAFT", "REAL_PAUSED" -> {
                String cid = it.create(code, trigger, null).path("id").asString();
                if ("REAL_PAUSED".equals(scenario)) {
                    it.transition(cid, MARKETING, "PUBLISH", null, 200);
                    it.transition(cid, MARKETING, "PAUSE", null, 200);
                }
                it.registerMember(member, "SILVER");
                assertThat(realReason(id + "-A", trigger, member, TUESDAY, code)).as(id + " non candidata").isEqualTo("ABSENT");
            }
            case "FULL_TYPE" -> {
                live(code, trigger, null);
                it.registerMember(member, "SILVER");
                JsonNode sim = simulate("io.loyaltyhub.action." + trigger, TUESDAY, null, member, null, null).body();
                assertThat(reasonIn(sim.path("results"), code)).as(id).isEqualTo("MATCHED");
            }
            case "NO_TYPE" -> {
                ObjectNode req = it.mapper.createObjectNode();
                req.putObject("action").put("time", TUESDAY);
                it.expect("POST", "/v1/campaigns/simulate", MARKETING, req, 400);
            }
            case "BAD_TIME" -> {
                ObjectNode req = it.mapper.createObjectNode();
                req.putObject("action").put("type", trigger).put("time", "ieri alle nove");
                it.expect("POST", "/v1/campaigns/simulate", MARKETING, req, 400);
            }
            case "OVERRIDE_TIER" -> {
                live(code, trigger, Map.of("audience", Map.of("all", false, "tiers", List.of("GOLD"), "segments", List.of())));
                it.registerMember(member, "SILVER");
                assertThat(reasonIn(simulate(trigger, TUESDAY, null, member, null, null).body().path("results"), code))
                        .as(id + " senza override").isEqualTo("AUDIENCE");
                JsonNode sim = simulate(trigger, TUESDAY, null, member, null, Map.of("tier", "GOLD")).body();
                assertThat(reasonIn(sim.path("results"), code)).as(id + " con tier ipotetico").isEqualTo("MATCHED");
            }
            case "OVERRIDE_NO_MEMBER" -> {
                live(code, trigger, null);
                JsonNode sim = simulate(trigger, TUESDAY, null, null, null, Map.of("tier", "SILVER")).body();
                assertThat(reasonIn(sim.path("results"), code)).as(id).isEqualTo("MATCHED");
            }
            case "UNKNOWN_MEMBER" -> {
                live(code, trigger, null);
                assertThat(simulate(trigger, TUESDAY, null, member, null, null).body().path("outcome").asString())
                        .as(id).isEqualTo("NO_MEMBER");
            }
            case "HISTORY" -> {
                live(code, trigger, Map.of("conditions", Map.of("op", "all", "rules",
                        List.of(Map.of("field", "history.actionCount", "cmp", "eq", "value", 0)))));
                it.registerMember(member, "SILVER");
                assertThat(simReason(trigger, member, code)).as(id + " simulazione prima di ogni azione").isEqualTo("MATCHED");
                assertThat(realReason(id + "-A", trigger, member, TUESDAY, code)).as(id + " prima azione").isEqualTo("MATCHED");
                assertThat(realReason(id + "-B", trigger, member, "2026-09-15T10:00:00Z", code)).as(id + " seconda azione")
                        .isEqualTo("CONDITION");
            }
            case "COOLDOWN" -> {
                live(code, trigger, Map.of("limits", Map.of("cooldownMinutes", 60)));
                it.registerMember(member, "SILVER");
                assertThat(realReason(id + "-A", trigger, member, TUESDAY, code)).as(id + " prima azione").isEqualTo("MATCHED");
                assertThat(realReason(id + "-B", trigger, member, "2026-09-15T09:10:00Z", code))
                        .as(id + " seconda azione dentro il cooldown (F-CMP-05)").isNotEqualTo("MATCHED");
            }
            case "BUDGET" -> {
                live(code, trigger, Map.of("limits", Map.of("global", Map.of("maxMatches", 1))));
                it.registerMember(member, "SILVER");
                assertThat(realReason(id + "-A", trigger, member, TUESDAY, code)).as(id + " prima azione").isEqualTo("MATCHED");
                assertThat(realReason(id + "-B", trigger, member, "2026-09-15T10:00:00Z", code)).as(id + " seconda azione")
                        .isEqualTo("BUDGET");
            }
            case "DAY_LIMIT" -> {
                live(code, trigger, Map.of("limits", Map.of("perMember", List.of(Map.of("max", 1, "period", "DAY")))));
                it.registerMember(member, "SILVER");
                assertThat(realReason(id + "-A", trigger, member, TUESDAY, code)).as(id + " 11:00 di Roma").isEqualTo("MATCHED");
                assertThat(realReason(id + "-B", trigger, member, "2026-09-15T21:59:59Z", code)).as(id + " 23:59:59 di Roma")
                        .isEqualTo("LIMIT");
                assertThat(realReason(id + "-C", trigger, member, "2026-09-15T22:00:00Z", code)).as(id + " 00:00 di Roma del 16")
                        .isEqualTo("MATCHED");
            }
            case "BLOCKED" -> {
                live(code, trigger, null);
                it.registerMember(member, "SILVER");
                it.fact("member.status.changed", member, Map.of("previousStatus", "ACTIVE", "newStatus", "BLOCKED"));
                it.await("snapshot BLOCKED di " + member, () -> it.simulateOutcome(member), "NO_MEMBER"::equals);
                it.action(id + "-A", trigger, member, TUESDAY, null);
                assertThat(it.awaitOutcome(member, id + "-A")).as(id).isEqualTo("NO_MEMBER");
            }
            case "DUPLICATE_DELIVERY" -> {
                live(code, trigger, Map.of("limits", Map.of("perMember", List.of(Map.of("max", 2, "period", "ALWAYS")))));
                it.registerMember(member, "SILVER");
                it.action(id + "-A", trigger, member, TUESDAY, null);
                assertThat(realReason(id + "-A", trigger, member, TUESDAY, code)).as(id + " azione consegnata due volte")
                        .isEqualTo("MATCHED");
                assertThat(realReason(id + "-B", trigger, member, "2026-09-15T10:00:00Z", code))
                        .as(id + " il secondo match del limite 2 è ancora disponibile").isEqualTo("MATCHED");
            }
            case "PER_MEMBER_POINTS" -> {
                // docs/03 §3.2 (perMemberPoints) e F-CMP-05 «tetto punti per membro»: con 100 già ricevuti non si supera.
                live(code, trigger, Map.of("limits", Map.of("perMemberPoints", 100),
                        "effects", List.of(Map.of("type", "GRANT_POINTS", "currency", "PTS", "mode", "FIXED", "value", 100))));
                it.registerMember(member, "SILVER");
                assertThat(realReason(id + "-A", trigger, member, TUESDAY, code)).as(id + " primi 100 punti").isEqualTo("MATCHED");
                assertThat(realReason(id + "-B", trigger, member, "2026-09-15T10:00:00Z", code))
                        .as(id + " oltre il tetto punti per membro (F-CMP-05)").isNotEqualTo("MATCHED");
            }
            case "UNKNOWN_EVALUATION" -> it.expect("GET", "/v1/evaluations/TB-NESSUNA-AZIONE", "ANALYST:sara", null, 404);
            case "CUSTOM_TYPE" -> {
                String custom = trigger + ".eco.pledge.signed";
                live(code, custom, null);
                it.registerMember(member, "SILVER");
                assertThat(realReason(id + "-A", custom, member, TUESDAY, code)).as(id).isEqualTo("MATCHED");
            }
            default -> throw new IllegalArgumentException(scenario);
        }
    }

    // ---------- supporto ----------

    private static String trigger(String rowId) {
        return "tbcmp." + rowId.substring("TB-CMP-".length()).replace("-", "").toLowerCase();
    }

    /** Membro fresco per riga: MBR-91nnnn (EQV) o MBR-92nnnn (SIM). */
    private static String member(String rowId) {
        String n = rowId.substring(rowId.lastIndexOf('-') + 1);
        return (rowId.contains("-EQV-") ? "MBR-91" : "MBR-92") + String.format("%04d", Integer.parseInt(n));
    }

    private static Map<String, Object> limitOnce() {
        return Map.of("limits", Map.of("perMember", List.of(Map.of("max", 1, "period", "ALWAYS"))));
    }

    @SuppressWarnings("unchecked")
    private String live(String code, String trigger, Map<?, ?> overrides) {
        String cid = it.create(code, trigger, (Map<String, Object>) overrides).path("id").asString();
        it.transition(cid, MARKETING, "PUBLISH", null, 200);
        return cid;
    }

    private Resp simulate(String type, String time, JsonNode data, String memberId, List<String> campaignIds,
                          Map<String, Object> override) {
        Map<String, Object> action = new HashMap<>();
        action.put("type", type);
        action.put("time", time);
        action.put("source", "urn:loyaltyhub:source:ecommerce");
        action.put("data", data == null ? Map.of() : data);
        Map<String, Object> req = new HashMap<>();
        req.put("action", action);
        if (memberId != null) {
            req.put("memberId", memberId);
        }
        if (campaignIds != null) {
            req.put("campaignIds", campaignIds);
        }
        if (override != null) {
            req.put("memberOverride", override);
        }
        return it.expect("POST", "/v1/campaigns/simulate", MARKETING, req, 200);
    }

    private String simReason(String trigger, String member, String code) {
        return reasonIn(simulate(trigger, TUESDAY, null, member, null, null).body().path("results"), code);
    }

    private String realReason(String actionId, String trigger, String member, String time, String code) {
        it.action(actionId, trigger, member, time, null);
        return reasonIn(it.awaitEvaluation(actionId), code);
    }

    /** Esito di una campagna nei risultati: MATCHED, il motivo, oppure ABSENT se non candidata. */
    private static String reasonIn(JsonNode results, String code) {
        for (JsonNode r : results) {
            if (code.equals(r.path("campaignCode").asString())) {
                return r.path("matched").asBoolean() ? "MATCHED" : r.path("reason").asString();
            }
        }
        return "ABSENT";
    }
}
