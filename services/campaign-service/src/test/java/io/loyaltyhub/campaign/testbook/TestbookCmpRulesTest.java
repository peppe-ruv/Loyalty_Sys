package io.loyaltyhub.campaign.testbook;

import tools.jackson.databind.JsonNode;
import io.loyaltyhub.campaign.domain.Campaign;
import io.loyaltyhub.campaign.domain.CampaignStatus;
import io.loyaltyhub.campaign.engine.CampaignEngine;
import io.loyaltyhub.campaign.engine.ConditionEvaluator;
import io.loyaltyhub.campaign.engine.Counters;
import io.loyaltyhub.campaign.engine.EvalAction;
import io.loyaltyhub.campaign.engine.Evaluation;
import io.loyaltyhub.campaign.engine.GrantedEffect;
import io.loyaltyhub.campaign.engine.MemberSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class TestbookCmpRulesTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CampaignEngine engine = new CampaignEngine(new ConditionEvaluator(), 5.0);
    private final Counters zeroCounters = new Counters() {
        @Override public int memberMatches(String c, String m, String p, String k) { return 0; }
        @Override public long globalPointsDecided(String c) { return 0; }
        @Override public long globalMatches(String c) { return 0; }
        @Override public long historyActionCount(String m, String t) { return 0; }
        @Override public long historyDaysSinceLastAction(String m, String t) { return -1; }
    };

    @ParameterizedTest(name = "[{0}] {1}")
    @CsvFileSource(resources = "/testbook/campaign/triggers.csv", numLinesToSkip = 1)
    void checkTriggers(String id, String actionTrigger, String cmpTrigger, String expectedOutcome, String expectedReason) {
        Campaign cmp = campaign("CMP-TRIG", List.of(cmpTrigger), "{\"op\":\"all\",\"rules\":[]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":10}]", "{}");

        EvalAction action = action(actionTrigger, Instant.parse("2026-06-15T12:00:00Z"), mapper.createObjectNode());
        MemberSnapshot member = silver();

        Evaluation ev = engine.evaluate(action, member, List.of(cmp), zeroCounters);

        assertThat(ev.outcome().name()).isEqualTo(expectedOutcome);
        if ("NO_MATCH".equals(expectedOutcome) && expectedReason != null) {
            assertThat(ev.results().get(0).reason().name()).isEqualTo(expectedReason);
        }
    }

    @ParameterizedTest(name = "[{0}] {1}")
    @CsvFileSource(resources = "/testbook/campaign/schedule.csv", numLinesToSkip = 1)
    void checkSchedule(String id, String actionTimeStr, String startAtStr, String endAtStr, String daysOfWeek, String hours, String expectedOutcome, String expectedReason) {
        String scheduleJson = String.format("{\"startAt\":\"%s\",\"endAt\":\"%s\",\"daysOfWeek\":[%s],\"hours\":[%s]}",
                startAtStr, endAtStr,
                daysOfWeek == null ? "" : "\"" + daysOfWeek + "\"",
                hours == null ? "" : hours);

        Campaign cmp = new Campaign("id-CMP-SCHED", "CMP-SCHED", "Schedule", null, null, null, List.of("purchase.completed"),
                mapper.createObjectNode().put("all", true), node("{\"op\":\"all\",\"rules\":[]}"),
                node("[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":10}]"),
                node("{}"), node(scheduleJson), 100, null, true, false, false, List.of(),
                CampaignStatus.LIVE, 0, null, null);

        EvalAction action = action("purchase.completed", Instant.parse(actionTimeStr), mapper.createObjectNode());
        MemberSnapshot member = silver();

        Evaluation ev = engine.evaluate(action, member, List.of(cmp), zeroCounters);

        assertThat(ev.outcome().name()).isEqualTo(expectedOutcome);
        if ("NO_MATCH".equals(expectedOutcome) && expectedReason != null) {
            assertThat(ev.results().get(0).reason().name()).isEqualTo(expectedReason);
        }
    }

    @ParameterizedTest(name = "[{0}] {1}")
    @CsvFileSource(resources = "/testbook/campaign/audience.csv", numLinesToSkip = 1)
    void checkAudience(String id, boolean all, String tiers, String segments, String memberTier, String memberSegments, String expectedOutcome, String expectedReason) {
        String tiersJson = tiers == null ? "[]" : Arrays.stream(tiers.split(";")).map(t -> "\"" + t + "\"").collect(Collectors.joining(",", "[", "]"));
        String segmentsJson = segments == null ? "[]" : Arrays.stream(segments.split(";")).map(s -> "\"" + s + "\"").collect(Collectors.joining(",", "[", "]"));

        String audienceJson = String.format("{\"all\":%b,\"tiers\":%s,\"segments\":%s}", all, tiersJson, segmentsJson);

        Campaign cmp = new Campaign("id-CMP-AUD", "CMP-AUD", "Audience", null, null, null, List.of("purchase.completed"),
                node(audienceJson), node("{\"op\":\"all\",\"rules\":[]}"),
                node("[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":10}]"),
                node("{}"), mapper.createObjectNode(), 100, null, true, false, false, List.of(),
                CampaignStatus.LIVE, 0, null, null);

        List<String> memberSegList = memberSegments == null ? List.of() : Arrays.asList(memberSegments.split(";"));
        MemberSnapshot member = new MemberSnapshot("MBR-1", "ACTIVE", memberTier, memberSegList, List.of(), mapper.createObjectNode(), Instant.parse("2026-06-15T12:00:00Z"), null);

        EvalAction action = action("purchase.completed", Instant.parse("2026-06-15T12:00:00Z"), mapper.createObjectNode());

        Evaluation ev = engine.evaluate(action, member, List.of(cmp), zeroCounters);

        assertThat(ev.outcome().name()).isEqualTo(expectedOutcome);
        if ("NO_MATCH".equals(expectedOutcome) && expectedReason != null) {
            assertThat(ev.results().get(0).reason().name()).isEqualTo(expectedReason);
        }
    }

    @ParameterizedTest(name = "[{0}] {1}")
    @CsvFileSource(resources = "/testbook/campaign/conditions.csv", numLinesToSkip = 1)
    void checkConditions(String id, String conditionsJson, String actionDataJson, String memberAttributesJson, String contextJson, String historyJson, boolean expectedMatch) {
        Campaign cmp = campaign("CMP-COND", List.of("purchase.completed"), conditionsJson,
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":10}]", "{}");

        EvalAction action = action("purchase.completed", Instant.parse("2026-06-15T12:00:00Z"), node(actionDataJson));
        MemberSnapshot member = new MemberSnapshot("MBR-1", "ACTIVE", "SILVER", List.of(), List.of(), node(memberAttributesJson), Instant.parse("2026-06-15T12:00:00Z"), null);

        Evaluation ev = engine.evaluate(action, member, List.of(cmp), zeroCounters);

        if (expectedMatch) {
            assertThat(ev.outcome()).isEqualTo(Evaluation.Outcome.MATCHED);
            assertThat(ev.results().get(0).matched()).isTrue();
        } else {
            assertThat(ev.outcome()).isEqualTo(Evaluation.Outcome.NO_MATCH);
            if (ev.results().size() > 0) {
                 assertThat(ev.results().get(0).matched()).isFalse();
                 assertThat(ev.results().get(0).reason()).isEqualTo(Evaluation.SkipReason.CONDITION);
            }
        }
    }

    @ParameterizedTest(name = "[{0}] {1}")
    @CsvFileSource(resources = "/testbook/campaign/effects.csv", numLinesToSkip = 1)
    void checkEffects(String id, String effectsJson, String actionDataJson, long expectedAmount) {
        Campaign cmp = campaign("CMP-EFF", List.of("purchase.completed"), "{\"op\":\"all\",\"rules\":[]}", effectsJson, "{}");

        EvalAction action = action("purchase.completed", Instant.parse("2026-06-15T12:00:00Z"), node(actionDataJson));

        Evaluation ev = engine.evaluate(action, silver(), List.of(cmp), zeroCounters);

        if (expectedAmount > 0) {
            assertThat(ev.outcome()).isEqualTo(Evaluation.Outcome.MATCHED);
            GrantedEffect effect = ev.effects().stream().filter(e -> e.currency().equals("PTS")).findFirst().orElseThrow();
            assertThat(effect.amount()).isEqualTo(expectedAmount);
        } else {
            // Se eroga 0, l'effetto non deve essere presente
            boolean hasPts = ev.effects().stream().anyMatch(e -> "PTS".equals(e.currency()));
            assertThat(hasPts).isFalse();
        }
    }

    @Test    @org.junit.jupiter.api.DisplayName("[TB-CMP-EXCL-01] Due campagne stesso trigger, gruppo esclusivo identico")
    void checkExclusiveGroupPriority() {
        Campaign cmp1 = new Campaign("id-1", "CMP-1", "Cmp 1", null, null, null, List.of("purchase.completed"),
                mapper.createObjectNode().put("all", true), node("{\"op\":\"all\",\"rules\":[]}"),
                node("[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":10}]"),
                node("{}"), mapper.createObjectNode(), 200, "GROUP_A", true, false, false, List.of(),
                CampaignStatus.LIVE, 0, null, null);

        Campaign cmp2 = new Campaign("id-2", "CMP-2", "Cmp 2", null, null, null, List.of("purchase.completed"),
                mapper.createObjectNode().put("all", true), node("{\"op\":\"all\",\"rules\":[]}"),
                node("[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":20}]"),
                node("{}"), mapper.createObjectNode(), 100, "GROUP_A", true, false, false, List.of(),
                CampaignStatus.LIVE, 0, null, null);

        EvalAction action = action("purchase.completed", Instant.parse("2026-06-15T12:00:00Z"), mapper.createObjectNode());

        Evaluation ev = engine.evaluate(action, silver(), List.of(cmp1, cmp2), zeroCounters);

        assertThat(ev.outcome()).isEqualTo(Evaluation.Outcome.MATCHED);
        assertThat(ev.effects()).hasSize(1);
        assertThat(ev.effects().get(0).amount()).isEqualTo(10); // ha vinto cmp1

        assertThat(ev.results()).hasSize(2);
        assertThat(ev.results().get(1).campaignCode()).isEqualTo("CMP-2");
        assertThat(ev.results().get(1).reason()).isEqualTo(Evaluation.SkipReason.EXCLUSIVE);
    }

    @Test    @org.junit.jupiter.api.DisplayName("[TB-CMP-COND-03] Comparatore mismatch tipo su date (divergenza q-91)")
    void testbookAmbiguousDateIsoGtDivergence() {
        // // TESTBOOK: ambiguo, vedi TB-CMP-COND-03
        // La Q-91 specifica che le date ISO sui comparatori relazionali funzionano solo con eq, fallendo silentemente su tra stringhe.
        // Verifichiamo che gt su date diverga o mantenga l'implementazione corrente.
        Campaign cmp = campaign("CMP-COND-DATE", List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":[{\"field\":\"context.date\",\"cmp\":\"gt\",\"value\":\"2026-01-01\"}]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":10}]", "{}");

        EvalAction action = action("purchase.completed", Instant.parse("2026-06-15T12:00:00Z"), node("{}"));

        Evaluation ev = engine.evaluate(action, silver(), List.of(cmp), zeroCounters);

        // Attualmente ConditionEvaluator.compareNode non supporta greater than tra stringhe (solo tra numeri).
        // Quindi ritornerà false (NO_MATCH). Questo documenta la divergenza.
        assertThat(ev.outcome()).isEqualTo(Evaluation.Outcome.NO_MATCH);
    }

    private Campaign campaign(String code, List<String> triggers, String conditions, String effects, String limits) {
        return new Campaign("id-" + code, code, code, null, null, null, triggers,
                mapper.createObjectNode().put("all", true), node(conditions), node(effects), node(limits),
                mapper.createObjectNode(), 100, null, true, false, false, List.of(),
                CampaignStatus.LIVE, 0, null, null);
    }

    private EvalAction action(String type, Instant t, JsonNode data) {
        return new EvalAction("01ACT" + t.toEpochMilli(), type, "MBR-1", "source", t, data);
    }

    private MemberSnapshot silver() {
        return new MemberSnapshot("MBR-1", "ACTIVE", "SILVER", List.of(), List.of(),
                mapper.createObjectNode(), Instant.parse("2024-01-01T00:00:00Z"), null);
    }

    private JsonNode node(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Test alias per completare la copertura delle righe nel testbook

    @Test
    @org.junit.jupiter.api.DisplayName("[TB-CMP-LIM-01] Incremento entro i limiti (perMember.DAY a 2 di 3 max)")
    void checkLim01() {
        Campaign cmp = campaign("CMP-LIM-01", List.of("purchase.completed"), "{\"op\":\"all\",\"rules\":[]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":10}]",
                "{\"perMember\":[{\"max\":3,\"period\":\"DAY\"}]}");

        Counters limitCounters = new Counters() {
            @Override public int memberMatches(String c, String m, String p, String k) { return 2; }
            @Override public long globalPointsDecided(String c) { return 0; }
            @Override public long globalMatches(String c) { return 0; }
            @Override public long historyActionCount(String m, String t) { return 0; }
            @Override public long historyDaysSinceLastAction(String m, String t) { return -1; }
        };

        Evaluation ev = engine.evaluate(action("purchase.completed", Instant.parse("2026-06-15T12:00:00Z"), node("{}")), silver(), List.of(cmp), limitCounters);
        assertThat(ev.outcome()).isEqualTo(Evaluation.Outcome.MATCHED);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("[TB-CMP-LIM-02] Incremento sfora limiti globali maxPoints")
    void checkLim02() {
        Campaign cmp = campaign("CMP-LIM-02", List.of("purchase.completed"), "{\"op\":\"all\",\"rules\":[]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":10}]",
                "{\"global\":{\"maxPoints\":100}}");

        Counters limitCounters = new Counters() {
            @Override public int memberMatches(String c, String m, String p, String k) { return 0; }
            @Override public long globalPointsDecided(String c) { return 95; }
            @Override public long globalMatches(String c) { return 0; }
            @Override public long historyActionCount(String m, String t) { return 0; }
            @Override public long historyDaysSinceLastAction(String m, String t) { return -1; }
        };

        Evaluation ev = engine.evaluate(action("purchase.completed", Instant.parse("2026-06-15T12:00:00Z"), node("{}")), silver(), List.of(cmp), limitCounters);
        assertThat(ev.outcome()).isEqualTo(Evaluation.Outcome.NO_MATCH);
        assertThat(ev.results().get(0).reason()).isEqualTo(Evaluation.SkipReason.BUDGET);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("[TB-CMP-LIM-04] Supera il tetto global.maxMatches")
    void checkLim04() {
        Campaign cmp = campaign("CMP-LIM-04", List.of("purchase.completed"), "{\"op\":\"all\",\"rules\":[]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":10}]",
                "{\"global\":{\"maxMatches\":10}}");

        Counters limitCounters = new Counters() {
            @Override public int memberMatches(String c, String m, String p, String k) { return 0; }
            @Override public long globalPointsDecided(String c) { return 0; }
            @Override public long globalMatches(String c) { return 10; }
            @Override public long historyActionCount(String m, String t) { return 0; }
            @Override public long historyDaysSinceLastAction(String m, String t) { return -1; }
        };

        Evaluation ev = engine.evaluate(action("purchase.completed", Instant.parse("2026-06-15T12:00:00Z"), node("{}")), silver(), List.of(cmp), limitCounters);
        assertThat(ev.outcome()).isEqualTo(Evaluation.Outcome.NO_MATCH);
        assertThat(ev.results().get(0).reason()).isEqualTo(Evaluation.SkipReason.BUDGET);
    }

}
