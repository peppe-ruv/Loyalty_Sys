package io.loyaltyhub.campaign.engine;

import io.loyaltyhub.campaign.domain.Campaign;
import io.loyaltyhub.campaign.domain.CampaignStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

public class CampaignEnginePropertyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private static final int RUNS = 500;

    private static final long BASE_SEED;
    static {
        String seedProp = System.getProperty("prop.seed");
        BASE_SEED = seedProp != null ? Long.parseLong(seedProp) : System.nanoTime();
    }

    private long seed;
    private Random random;
    private CampaignEngine engine;

    @BeforeEach
    void setUp(org.junit.jupiter.api.RepetitionInfo repetitionInfo) {
        // Combine the base seed with the repetition index so each run is unique but deterministic
        seed = BASE_SEED + repetitionInfo.getCurrentRepetition();
        random = new Random(seed);
        engine = new CampaignEngine(5);
    }

    private void failWithSeed(String message, Throwable cause) {
        throw new AssertionError("Seed " + seed + " - " + message, cause);
    }

    // --- Generators ---

    private String randomString(int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append((char) ('a' + random.nextInt(26)));
        }
        return sb.toString();
    }

    private Instant randomInstant() {
        long min = ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, ROME).toInstant().toEpochMilli();
        long max = ZonedDateTime.of(2030, 1, 1, 0, 0, 0, 0, ROME).toInstant().toEpochMilli();
        return Instant.ofEpochMilli(min + (long)(random.nextDouble() * (max - min)));
    }

    private MemberSnapshot randomMember() {
        boolean active = random.nextDouble() > 0.1;
        String[] tiers = {"BRONZE", "SILVER", "GOLD", "PLATINUM"};
        String tier = tiers[random.nextInt(tiers.length)];

        List<String> segments = new ArrayList<>();
        if (random.nextBoolean()) segments.add("vip");
        if (random.nextBoolean()) segments.add("early_adopter");

        List<String> labels = new ArrayList<>();
        if (random.nextBoolean()) labels.add("tester");

        ObjectNode attrs = MAPPER.createObjectNode();
        attrs.put("score", random.nextInt(100));

        return new MemberSnapshot(
            "MBR-" + random.nextInt(10000),
            active ? "ACTIVE" : "INACTIVE",
            tier,
            segments,
            labels,
            attrs,
            randomInstant(),
            LocalDate.of(1950 + random.nextInt(50), 1 + random.nextInt(12), 1 + random.nextInt(28))
        );
    }

    private EvalAction randomAction(String type, ObjectNode data) {
        return new EvalAction(
            "ACT-" + randomString(8),
            type,
            "MBR-" + random.nextInt(10000),
            "source-" + random.nextInt(5),
            randomInstant(),
            data
        );
    }

    private Campaign randomCampaign(List<String> triggers, JsonNode effects) {
        try {
            return new Campaign(
                "id-" + randomString(6),
                "CMP-" + randomString(4),
                "Name " + randomString(4),
                null, null, null,
                triggers,
                MAPPER.createObjectNode().put("all", true),
                MAPPER.readTree("{\"op\":\"all\",\"rules\":[]}"),
                effects,
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode(),
                random.nextInt(100),
                random.nextBoolean() ? "EXC-" + random.nextInt(3) : null,
                true, false, false, List.of(),
                CampaignStatus.LIVE, 0, null, null
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Counters mockCounters(int matches, long points, long actionCount, long daysSinceLast) {
        return new Counters() {
            public int memberMatches(String c, String m, String p, String k) { return matches; }
            public long globalPointsDecided(String c) { return points; }
            public long globalMatches(String c) { return matches; }
            public long historyActionCount(String m, String t) { return actionCount; }
            public long historyDaysSinceLastAction(String m, String t) { return daysSinceLast; }
        };
    }

    // --- Tests ---

    @RepeatedTest(RUNS)
    void property1_determinism() {
        try {
            MemberSnapshot member = randomMember();
            ObjectNode data = MAPPER.createObjectNode();
            data.put("amount", random.nextDouble() * 1000);
            EvalAction action = randomAction("purchase", data);

            ArrayNode effects = MAPPER.createArrayNode();
            ObjectNode effect = MAPPER.createObjectNode();
            effect.put("type", "GRANT_POINTS");
            effect.put("currency", "PTS");
            effect.put("mode", "FIXED");
            effect.put("value", random.nextInt(100) + 1);
            effects.add(effect);

            Campaign campaign1 = randomCampaign(List.of("purchase"), effects);
            Campaign campaign2 = randomCampaign(List.of("purchase"), effects);
            List<Campaign> campaigns = List.of(campaign1, campaign2);

            Counters counters = mockCounters(random.nextInt(5), random.nextInt(1000), random.nextInt(10), random.nextInt(30));

            Evaluation eval1 = engine.evaluate(action, member, campaigns, counters);
            Evaluation eval2 = engine.evaluate(action, member, campaigns, counters);

            assertThat(eval1).isEqualTo(eval2);
        } catch (Throwable t) {
            failWithSeed("Property 1 failed", t);
        }
    }

    @RepeatedTest(RUNS)
    void property2_effectIdStability() {
        try {
            MemberSnapshot member = randomMember();
            member = new MemberSnapshot(member.memberId(), "ACTIVE", member.tier(), member.segments(), member.labels(), member.attributes(), member.registeredAt(), member.birthDate());
            ObjectNode data = MAPPER.createObjectNode();
            EvalAction action = randomAction("custom", data);

            ArrayNode effects = MAPPER.createArrayNode();
            int numEffects = random.nextInt(5) + 1;
            for (int i = 0; i < numEffects; i++) {
                ObjectNode effect = MAPPER.createObjectNode();
                effect.put("type", "GRANT_POINTS");
                effect.put("currency", "PTS");
                effect.put("mode", "FIXED");
                effect.put("value", random.nextInt(100) + 1);
                effects.add(effect);

                ObjectNode effect2 = MAPPER.createObjectNode();
                effect2.put("type", "ISSUE_COUPON");
                effect2.put("rewardCode", "RW-" + i);
                effects.add(effect2);
            }

            Campaign campaign = randomCampaign(List.of("custom"), effects);
            campaign = new Campaign(campaign.id(), campaign.code(), campaign.name(), null, null, null, campaign.triggerActionTypes(), campaign.audience(), MAPPER.readTree("{\"op\":\"all\",\"rules\":[]}"), effects, MAPPER.createObjectNode(), MAPPER.createObjectNode(), campaign.priority(), null, true, false, false, List.of(), CampaignStatus.LIVE, 0, null, null);

            Evaluation eval1 = engine.evaluate(action, member, List.of(campaign), mockCounters(0, 0, 0, 0));
            Evaluation eval2 = engine.evaluate(action, member, List.of(campaign), mockCounters(0, 0, 0, 0));

            Set<String> effectIds = new HashSet<>();
            for (int i = 0; i < eval1.effects().size(); i++) {
                GrantedEffect e1 = eval1.effects().get(i);
                GrantedEffect e2 = eval2.effects().get(i);

                assertThat(e1.effectId()).as("Seed: %d", seed).isEqualTo(e2.effectId());
                assertThat(effectIds.add(e1.effectId())).as("Seed: %d", seed).isTrue();

                // effect index should be the index in the original campaign effects array, not necessarily i since eval might skip some effects
                // However, we just know e1 is an effect that was granted. e1 has effectId.
                // In CampaignEngine: effectId = sha256(actionId + campaignCode + index)

                // For property 2, "sha256(actionId+code+i) prefix": this means the effectId should match one of the calculated prefixes
                boolean foundPrefix = false;
                for (int origIdx = 0; origIdx < campaign.effects().size(); origIdx++) {
                    String prefix = CampaignEngine.effectId(action.actionId(), campaign.code(), origIdx);
                    if (e1.effectId().startsWith(prefix)) {
                        foundPrefix = true;
                        break;
                    }
                }
                assertThat(foundPrefix).as("Seed: %d - effectId %s does not match any prefix", seed, e1.effectId()).isTrue();
            }
        } catch (Throwable t) {
            failWithSeed("Property 2 failed", t);
        }
    }

    @RepeatedTest(RUNS)
    void property3_perAmountMonotonic() {
        try {
            MemberSnapshot member = randomMember();
            member = new MemberSnapshot(member.memberId(), "ACTIVE", member.tier(), member.segments(), member.labels(), member.attributes(), member.registeredAt(), member.birthDate());

            double unitStep = random.nextDouble() * 10 + 0.1;
            long value = random.nextInt(50) + 1;
            String[] roundings = {"FLOOR", "CEIL", "ROUND"};
            String rounding = roundings[random.nextInt(roundings.length)];

            ArrayNode effects = MAPPER.createArrayNode();
            ObjectNode effect = MAPPER.createObjectNode();
            effect.put("type", "GRANT_POINTS");
            effect.put("currency", "PTS");
            effect.put("mode", "PER_AMOUNT");
            effect.put("amountField", "data.amount");
            effect.put("unitStep", unitStep);
            effect.put("value", value);
            effect.put("rounding", rounding);
            effects.add(effect);

            Campaign campaign = randomCampaign(List.of("purchase"), effects);
            campaign = new Campaign(campaign.id(), campaign.code(), campaign.name(), null, null, null, campaign.triggerActionTypes(), campaign.audience(), MAPPER.readTree("{\"op\":\"all\",\"rules\":[]}"), effects, MAPPER.createObjectNode(), MAPPER.createObjectNode(), campaign.priority(), null, true, false, false, List.of(), CampaignStatus.LIVE, 0, null, null);

            double amount1 = random.nextDouble() * 1000;
            double amount2 = amount1 + random.nextDouble() * 500;

            ObjectNode data1 = MAPPER.createObjectNode(); data1.put("amount", amount1);
            ObjectNode data2 = MAPPER.createObjectNode(); data2.put("amount", amount2);
            ObjectNode data0 = MAPPER.createObjectNode(); data0.put("amount", 0.0);
            ObjectNode dataNeg = MAPPER.createObjectNode(); dataNeg.put("amount", -random.nextDouble() * 100);

            EvalAction action1 = randomAction("purchase", data1);
            EvalAction action2 = randomAction("purchase", data2);
            EvalAction action0 = randomAction("purchase", data0);
            EvalAction actionNeg = randomAction("purchase", dataNeg);

            Counters counters = mockCounters(0, 0, 0, 0);

            Evaluation eval1 = engine.evaluate(action1, member, List.of(campaign), counters);
            Evaluation eval2 = engine.evaluate(action2, member, List.of(campaign), counters);
            Evaluation eval0 = engine.evaluate(action0, member, List.of(campaign), counters);
            Evaluation evalNeg = engine.evaluate(actionNeg, member, List.of(campaign), counters);

            long pts1 = eval1.effects().stream().mapToLong(GrantedEffect::amount).sum();
            long pts2 = eval2.effects().stream().mapToLong(GrantedEffect::amount).sum();
            long pts0 = eval0.effects().stream().mapToLong(GrantedEffect::amount).sum();
            long ptsNeg = evalNeg.effects().stream().mapToLong(GrantedEffect::amount).sum();

            assertThat(pts2).isGreaterThanOrEqualTo(pts1);
            assertThat(pts1).isGreaterThanOrEqualTo(0);
            assertThat(pts0).isEqualTo(0);
            assertThat(ptsNeg).isEqualTo(0);

        } catch (Throwable t) {
            failWithSeed("Property 3 failed", t);
        }
    }

    @RepeatedTest(RUNS)
    void property4_multipliersAndExclusiveGroups() {
        try {
            MemberSnapshot member = randomMember();
            member = new MemberSnapshot(member.memberId(), "ACTIVE", member.tier(), member.segments(), member.labels(), member.attributes(), member.registeredAt(), member.birthDate());
            ObjectNode data = MAPPER.createObjectNode();
            EvalAction action = randomAction("purchase", data);

            // Generate campaigns in an exclusive group
            String exclGroup = "EXC-1";
            ArrayNode effects1 = MAPPER.createArrayNode();
            ObjectNode effect1 = MAPPER.createObjectNode();
            effect1.put("type", "GRANT_POINTS"); effect1.put("currency", "PTS"); effect1.put("mode", "FIXED"); effect1.put("value", 10);
            effects1.add(effect1);

            ArrayNode effects2 = MAPPER.createArrayNode();
            ObjectNode effect2 = MAPPER.createObjectNode();
            effect2.put("type", "GRANT_POINTS"); effect2.put("currency", "PTS"); effect2.put("mode", "FIXED"); effect2.put("value", 20);
            effects2.add(effect2);

            Campaign c1 = randomCampaign(List.of("purchase"), effects1);
            c1 = new Campaign(c1.id(), c1.code(), c1.name(), null, null, null, c1.triggerActionTypes(), MAPPER.createObjectNode().put("all", true), MAPPER.readTree("{\"op\":\"all\",\"rules\":[]}"), effects1, MAPPER.createObjectNode(), MAPPER.createObjectNode(), 10, exclGroup, true, false, false, List.of("A"), CampaignStatus.LIVE, 0, null, null);

            Campaign c2 = randomCampaign(List.of("purchase"), effects2);
            c2 = new Campaign(c2.id(), c2.code(), c2.name(), null, null, null, c2.triggerActionTypes(), MAPPER.createObjectNode().put("all", true), MAPPER.readTree("{\"op\":\"all\",\"rules\":[]}"), effects2, MAPPER.createObjectNode(), MAPPER.createObjectNode(), 20, exclGroup, true, false, false, List.of("B"), CampaignStatus.LIVE, 0, null, null);

            // Generate a multiplier campaign
            double multFactor = 1.1 + random.nextDouble() * 3.9; // [1.1, 5.0]
            ArrayNode effectsMult = MAPPER.createArrayNode();
            ObjectNode effectMult = MAPPER.createObjectNode();
            effectMult.put("type", "MULTIPLIER"); effectMult.put("currency", "PTS"); effectMult.put("factor", multFactor); effectMult.put("scope", "ALL_GRANTS");
            effectsMult.add(effectMult);

            Campaign cMult = randomCampaign(List.of("purchase"), effectsMult);
            cMult = new Campaign(cMult.id(), cMult.code(), cMult.name(), null, null, null, cMult.triggerActionTypes(), MAPPER.createObjectNode().put("all", true), MAPPER.readTree("{\"op\":\"all\",\"rules\":[]}"), effectsMult, MAPPER.createObjectNode(), MAPPER.createObjectNode(), 5, null, true, false, false, List.of(), CampaignStatus.LIVE, 0, null, null);

            Evaluation eval = engine.evaluate(action, member, List.of(c1, c2, cMult), mockCounters(0, 0, 0, 0));

            // Exclusive group: only c2 (priority 20) should match, c1 (priority 10) should skip
            long pointsGrants = eval.effects().stream().filter(e -> e.currency().equals("PTS")).count();
            assertThat(pointsGrants).isEqualTo(1); // One from c2

            GrantedEffect ge = eval.effects().get(0);
            assertThat(ge.baseAmount()).isEqualTo(20);
            assertThat(ge.campaignMultiplier()).isEqualTo(multFactor);
            assertThat(ge.amount()).isEqualTo((long) Math.floor(20 * multFactor));
            assertThat(ge.amount()).isGreaterThanOrEqualTo(ge.baseAmount()); // Multiplier never lowers points

            // Ensure c1 was skipped
            String c1Code = c1.code();
            boolean c1Skipped = eval.results().stream().anyMatch(r -> r.campaignCode().equals(c1Code) && !r.matched() && r.reason() == Evaluation.SkipReason.EXCLUSIVE);
            assertThat(c1Skipped).isTrue();

        } catch (Throwable t) {
            failWithSeed("Property 4 failed", t);
        }
    }

    @RepeatedTest(RUNS)
    void property5_limitsAndBudgets() {
        try {
            MemberSnapshot member = randomMember();
            member = new MemberSnapshot(member.memberId(), "ACTIVE", member.tier(), member.segments(), member.labels(), member.attributes(), member.registeredAt(), member.birthDate());

            int maxPerMember = random.nextInt(5) + 1;
            long maxGlobalPts = (long) maxPerMember * 50 + random.nextInt(100);

            ArrayNode effects = MAPPER.createArrayNode();
            ObjectNode effect = MAPPER.createObjectNode();
            effect.put("type", "GRANT_POINTS"); effect.put("currency", "PTS"); effect.put("mode", "FIXED"); effect.put("value", 50);
            effects.add(effect);

            ObjectNode limits = MAPPER.createObjectNode();
            ArrayNode perMember = MAPPER.createArrayNode();
            ObjectNode limit1 = MAPPER.createObjectNode(); limit1.put("max", maxPerMember); limit1.put("period", "ALWAYS");
            perMember.add(limit1);
            limits.set("perMember", perMember);

            ObjectNode global = MAPPER.createObjectNode();
            global.put("maxPoints", maxGlobalPts);
            limits.set("global", global);

            Campaign c = randomCampaign(List.of("purchase"), effects);
            c = new Campaign(c.id(), c.code(), c.name(), null, null, null, c.triggerActionTypes(), MAPPER.createObjectNode().put("all", true), MAPPER.readTree("{\"op\":\"all\",\"rules\":[]}"), effects, limits, MAPPER.createObjectNode(), 10, null, true, false, false, List.of(), CampaignStatus.LIVE, 0, null, null);

            int actionsCount = maxPerMember + random.nextInt(5) + 1;
            int actualMatches = 0;
            long actualGlobalPts = 0;

            for (int i = 0; i < actionsCount; i++) {
                ObjectNode data = MAPPER.createObjectNode();
                EvalAction action = randomAction("purchase", data);

                final int currentMatches = actualMatches;
                final long currentGlobalPts = actualGlobalPts;

                Counters counters = new Counters() {
                    public int memberMatches(String cmp, String m, String p, String k) { return currentMatches; }
                    public long globalPointsDecided(String cmp) { return currentGlobalPts; }
                    public long globalMatches(String cmp) { return currentMatches; }
                    public long historyActionCount(String m, String t) { return 0; }
                    public long historyDaysSinceLastAction(String m, String t) { return -1; }
                };

                Evaluation eval = engine.evaluate(action, member, List.of(c), counters);

                if (currentMatches < maxPerMember && currentGlobalPts + 50 <= maxGlobalPts) {
                    assertThat(eval.outcome()).as("Seed: %d, iteration: %d", seed, i).isEqualTo(Evaluation.Outcome.MATCHED);
                    actualMatches++;
                    actualGlobalPts += 50;
                } else {
                    assertThat(eval.outcome()).as("Seed: %d, iteration: %d", seed, i).isEqualTo(Evaluation.Outcome.NO_MATCH);
                    Evaluation.SkipReason reason = eval.results().get(0).reason();
                    if (currentMatches >= maxPerMember) {
                        assertThat(reason).as("Seed: %d", seed).isEqualTo(Evaluation.SkipReason.LIMIT);
                    } else {
                        assertThat(reason).as("Seed: %d", seed).isEqualTo(Evaluation.SkipReason.BUDGET);
                    }
                }
            }
        } catch (Throwable t) {
            failWithSeed("Property 5 failed", t);
        }
    }

    private JsonNode generateRandomRuleTree(int depth) {
        if (depth == 0) {
            ObjectNode leaf = MAPPER.createObjectNode();
            String[] fields = {"member.tier", "context.dayOfWeek", "data.amount"};
            String[] cmps = {"eq", "neq", "gt", "lt"};
            leaf.put("field", fields[random.nextInt(fields.length)]);
            leaf.put("cmp", cmps[random.nextInt(cmps.length)]);
            leaf.put("value", randomString(4));
            return leaf;
        }

        ObjectNode node = MAPPER.createObjectNode();
        String[] ops = {"all", "any", "not"};
        String op = ops[random.nextInt(ops.length)];
        node.put("op", op);

        ArrayNode rules = MAPPER.createArrayNode();
        int childCount = op.equals("not") ? 1 : random.nextInt(3) + 1;
        for (int i = 0; i < childCount; i++) {
            rules.add(generateRandomRuleTree(depth - 1));
        }
        node.set("rules", rules);
        return node;
    }

    @RepeatedTest(RUNS)
    void property6_conditionsMetamorphic() {
        try {
            MemberSnapshot member = randomMember();
            member = new MemberSnapshot(member.memberId(), "ACTIVE", member.tier(), member.segments(), member.labels(), member.attributes(), member.registeredAt(), member.birthDate());
            ObjectNode data = MAPPER.createObjectNode();
            EvalAction action = randomAction("purchase", data);

            ArrayNode effects = MAPPER.createArrayNode();
            ObjectNode effect = MAPPER.createObjectNode();
            effect.put("type", "GRANT_POINTS"); effect.put("currency", "PTS"); effect.put("mode", "FIXED"); effect.put("value", 50);
            effects.add(effect);

            // Generate arbitrary rule T
            JsonNode baseTree = generateRandomRuleTree(random.nextInt(3) + 1);

            // Evaluate base T
            Campaign base = randomCampaign(List.of("purchase"), effects);
            Campaign cBase = new Campaign(base.id(), "CBASE", base.name(), null, null, null, base.triggerActionTypes(), MAPPER.createObjectNode().put("all", true), baseTree, effects, MAPPER.createObjectNode(), MAPPER.createObjectNode(), 10, null, true, false, false, List.of(), CampaignStatus.LIVE, 0, null, null);
            Counters counters = mockCounters(0, 0, 0, 0);
            Evaluation evalBase = engine.evaluate(action, member, List.of(cBase), counters);
            boolean baseResult = evalBase.outcome() == Evaluation.Outcome.MATCHED;

            // True leaf
            ObjectNode leafTrue = MAPPER.createObjectNode();
            leafTrue.put("field", "member.status"); leafTrue.put("cmp", "eq"); leafTrue.put("value", "ACTIVE");

            // False leaf
            ObjectNode leafFalse = MAPPER.createObjectNode();
            leafFalse.put("field", "member.status"); leafFalse.put("cmp", "eq"); leafFalse.put("value", "INACTIVE");

            // T AND TRUE -> baseResult
            ObjectNode ruleAllTrue = MAPPER.createObjectNode(); ruleAllTrue.put("op", "all");
            ArrayNode rtRules = MAPPER.createArrayNode(); rtRules.add(baseTree.deepCopy()); rtRules.add(leafTrue); ruleAllTrue.set("rules", rtRules);
            Campaign cAllTrue = new Campaign(base.id(), "CT", base.name(), null, null, null, base.triggerActionTypes(), MAPPER.createObjectNode().put("all", true), ruleAllTrue, effects, MAPPER.createObjectNode(), MAPPER.createObjectNode(), 10, null, true, false, false, List.of(), CampaignStatus.LIVE, 0, null, null);
            Evaluation evalAllTrue = engine.evaluate(action, member, List.of(cAllTrue), counters);
            assertThat(evalAllTrue.outcome() == Evaluation.Outcome.MATCHED).as("Seed: %d", seed).isEqualTo(baseResult);

            // T OR FALSE -> baseResult
            ObjectNode ruleAnyFalse = MAPPER.createObjectNode(); ruleAnyFalse.put("op", "any");
            ArrayNode ratRules = MAPPER.createArrayNode(); ratRules.add(baseTree.deepCopy()); ratRules.add(leafFalse); ruleAnyFalse.set("rules", ratRules);
            Campaign cAnyFalse = new Campaign(base.id(), "CAF", base.name(), null, null, null, base.triggerActionTypes(), MAPPER.createObjectNode().put("all", true), ruleAnyFalse, effects, MAPPER.createObjectNode(), MAPPER.createObjectNode(), 10, null, true, false, false, List.of(), CampaignStatus.LIVE, 0, null, null);
            Evaluation evalAnyFalse = engine.evaluate(action, member, List.of(cAnyFalse), counters);
            assertThat(evalAnyFalse.outcome() == Evaluation.Outcome.MATCHED).as("Seed: %d", seed).isEqualTo(baseResult);

            // NOT T -> !baseResult
            ObjectNode ruleNot = MAPPER.createObjectNode(); ruleNot.put("op", "not");
            ArrayNode rnotRules = MAPPER.createArrayNode(); rnotRules.add(baseTree.deepCopy()); ruleNot.set("rules", rnotRules);
            Campaign cNot = new Campaign(base.id(), "CNOT", base.name(), null, null, null, base.triggerActionTypes(), MAPPER.createObjectNode().put("all", true), ruleNot, effects, MAPPER.createObjectNode(), MAPPER.createObjectNode(), 10, null, true, false, false, List.of(), CampaignStatus.LIVE, 0, null, null);
            Evaluation evalNot = engine.evaluate(action, member, List.of(cNot), counters);
            assertThat(evalNot.outcome() == Evaluation.Outcome.MATCHED).as("Seed: %d", seed).isEqualTo(!baseResult);

        } catch (Throwable t) {
            failWithSeed("Property 6 failed", t);
        }
    }

    @RepeatedTest(RUNS)
    void property7_businessTime() {
        try {
            MemberSnapshot member = randomMember();
            member = new MemberSnapshot(member.memberId(), "ACTIVE", member.tier(), member.segments(), member.labels(), member.attributes(), member.registeredAt(), member.birthDate());
            ObjectNode data = MAPPER.createObjectNode();

            // March 29, 2026 is a Sunday, DST switch in Europe/Rome happens at 02:00 -> 03:00.
            Instant t1 = Instant.parse("2026-03-29T00:59:00Z"); // 01:59 CET (Sunday)
            Instant t2 = Instant.parse("2026-03-29T01:01:00Z"); // 03:01 CEST (Sunday)
            Instant t3 = Instant.parse("2026-03-28T23:01:00Z"); // 00:01 CET (Sunday)
            Instant t4 = Instant.parse("2026-03-28T22:59:00Z"); // 23:59 CET (Saturday)

            EvalAction action1 = randomAction("purchase", data);
            EvalAction action2 = randomAction("purchase", data);
            EvalAction action3 = randomAction("purchase", data);
            EvalAction action4 = randomAction("purchase", data);

            action1 = new EvalAction(action1.actionId(), action1.type(), action1.memberId(), action1.source(), t1, action1.data());
            action2 = new EvalAction(action2.actionId(), action2.type(), action2.memberId(), action2.source(), t2, action2.data());
            action3 = new EvalAction(action3.actionId(), action3.type(), action3.memberId(), action3.source(), t3, action3.data());
            action4 = new EvalAction(action4.actionId(), action4.type(), action4.memberId(), action4.source(), t4, action4.data());

            ArrayNode effects = MAPPER.createArrayNode();
            ObjectNode effect = MAPPER.createObjectNode();
            effect.put("type", "GRANT_POINTS"); effect.put("currency", "PTS"); effect.put("mode", "FIXED"); effect.put("value", 50);
            effects.add(effect);

            ObjectNode ruleDay = MAPPER.createObjectNode();
            ruleDay.put("field", "context.dayOfWeek"); ruleDay.put("cmp", "eq"); ruleDay.put("value", "SUN");
            ObjectNode ruleAll = MAPPER.createObjectNode(); ruleAll.put("op", "all");
            ArrayNode rtRules = MAPPER.createArrayNode(); rtRules.add(ruleDay); ruleAll.set("rules", rtRules);

            Campaign c = randomCampaign(List.of("purchase"), effects);
            c = new Campaign(c.id(), c.code(), c.name(), null, null, null, c.triggerActionTypes(), MAPPER.createObjectNode().put("all", true), ruleAll, effects, MAPPER.createObjectNode(), MAPPER.createObjectNode(), 10, null, true, false, false, List.of(), CampaignStatus.LIVE, 0, null, null);

            Counters counters = mockCounters(0, 0, 0, 0);

            Evaluation eval1 = engine.evaluate(action1, member, List.of(c), counters);
            Evaluation eval2 = engine.evaluate(action2, member, List.of(c), counters);
            Evaluation eval3 = engine.evaluate(action3, member, List.of(c), counters);
            Evaluation eval4 = engine.evaluate(action4, member, List.of(c), counters);

            assertThat(eval1.outcome()).isEqualTo(Evaluation.Outcome.MATCHED); // Sunday
            assertThat(eval2.outcome()).isEqualTo(Evaluation.Outcome.MATCHED); // Sunday
            assertThat(eval3.outcome()).isEqualTo(Evaluation.Outcome.MATCHED); // Sunday
            assertThat(eval4.outcome()).isEqualTo(Evaluation.Outcome.NO_MATCH); // Saturday

        } catch (Throwable t) {
            failWithSeed("Property 7 failed", t);
        }
    }
}
