package io.loyaltyhub.campaign.engine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.campaign.domain.Campaign;
import io.loyaltyhub.campaign.domain.CampaignStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Motore regole puro (docs/03 §3.5, docs/servizi/campaign-service.md §7). Verifica il calcolo canonico
 * (feriale vs weekend), il limite per membro e lo scarto {@code EFFECT_NOT_SUPPORTED_YET}, senza Spring.
 */
class CampaignEngineTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final CampaignEngine engine = new CampaignEngine(5);

    private static final Instant TUESDAY = Instant.parse("2026-09-15T10:00:00Z");
    private static final Instant SATURDAY = Instant.parse("2026-09-19T10:00:00Z");

    private final Counters zero = new Counters() {
        public int memberMatches(String c, String m, String p, String k) {
            return 0;
        }

        public long globalPointsDecided(String c) {
            return 0;
        }

        public long globalMatches(String c) {
            return 0;
        }

        public long historyActionCount(String m, String t) {
            return 0;
        }

        public long historyDaysSinceLastAction(String m, String t) {
            return -1;
        }
    };

    @Test
    void purchaseWeekdaySilverGrantsBasePointsWithoutCampaignMultiplier() {
        Evaluation ev = engine.evaluate(purchase(TUESDAY, 130), silver(),
                List.of(purchaseBase(), weekendX2()), zero);

        assertThat(ev.outcome()).isEqualTo(Evaluation.Outcome.MATCHED);
        GrantedEffect pts = grant(ev, "PTS");
        assertThat(pts.baseAmount()).isEqualTo(130);
        assertThat(pts.campaignMultiplier()).isEqualTo(1.0);
        assertThat(pts.amount()).isEqualTo(130);       // il tier lo applica il wallet a valle
        assertThat(pts.tierMultiplierApplies()).isTrue();
        assertThat(grant(ev, "STS").amount()).isEqualTo(130);
    }

    @Test
    void purchaseWeekendAppliesCampaignMultiplierToPtsOnly() {
        Evaluation ev = engine.evaluate(purchase(SATURDAY, 130), silver(),
                List.of(purchaseBase(), weekendX2()), zero);

        assertThat(grant(ev, "PTS").campaignMultiplier()).isEqualTo(2.0);
        assertThat(grant(ev, "PTS").amount()).isEqualTo(260);   // 130 × 2, tier a valle → 325 nel wallet
        assertThat(grant(ev, "STS").amount()).isEqualTo(130);   // STS non moltiplicato
    }

    @Test
    void perMemberLimitReachedIsSkippedWithLimit() {
        Counters atLimit = new Counters() {
            public int memberMatches(String c, String m, String p, String k) {
                return 1; // già usato oggi
            }

            public long globalPointsDecided(String c) {
                return 0;
            }

            public long globalMatches(String c) {
                return 0;
            }

            public long historyActionCount(String m, String t) {
                return 0;
            }

            public long historyDaysSinceLastAction(String m, String t) {
                return -1;
            }
        };
        Evaluation ev = engine.evaluate(action("app.login.daily", TUESDAY, JSON.createObjectNode()),
                silver(), List.of(appDaily()), atLimit);

        assertThat(ev.outcome()).isEqualTo(Evaluation.Outcome.NO_MATCH);
        assertThat(ev.results().get(0).reason()).isEqualTo(Evaluation.SkipReason.LIMIT);
    }

    @Test
    void unsupportedEffectIsLoadedButNotEvaluated() {
        Evaluation ev = engine.evaluate(action("member.birthday", TUESDAY, JSON.createObjectNode()), silver(),
                List.of(birthday()), zero);

        assertThat(ev.effects()).isEmpty();
        assertThat(ev.results().get(0).reason()).isEqualTo(Evaluation.SkipReason.EFFECT_NOT_SUPPORTED_YET);
    }

    @Test
    void instantWinPrizesAreDeliveredBySystemCampaigns() {
        List<Campaign> system = List.of(iwPoints(), iwCoupon());
        Evaluation points = engine.evaluate(action("instantwin.won", TUESDAY, JSON.createObjectNode()
                .put("contestCode", "IW-AUTUNNO").put("prizeCode", "PTS-50").put("prizeType", "POINTS").put("points", 50)),
                silver(), system, zero);
        assertThat(points.effects()).hasSize(1);
        assertThat(points.effects().get(0).amount()).as("punti del premio, senza moltiplicatore di livello").isEqualTo(50);
        assertThat(points.effects().get(0).tierMultiplierApplies()).isFalse();
        assertThat(points.actionEffects()).isEmpty();

        Evaluation coupon = engine.evaluate(action("instantwin.won", TUESDAY, JSON.createObjectNode()
                .put("contestCode", "IW-AUTUNNO").put("prizeCode", "COFFEE").put("prizeType", "COUPON").put("rewardCode", "RWD-COFFEE-5")),
                silver(), system, zero);
        assertThat(coupon.effects()).isEmpty();
        assertThat(coupon.actionEffects()).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo("ISSUE_COUPON");
            assertThat(e.campaignCode()).isEqualTo("CMP-IW-PRIZE-COUPON");
            assertThat(e.params().path("rewardCode").asString()).isEqualTo("RWD-COFFEE-5");
        });

        Evaluation noReward = engine.evaluate(action("instantwin.won", TUESDAY, JSON.createObjectNode()
                .put("prizeType", "COUPON")), silver(), List.of(iwCoupon()), zero);
        assertThat(noReward.actionEffects()).as("premio non risolvibile → nessun effetto").isEmpty();
    }

    @Test
    void grantPlaysBecomesAnActionEffectNextToThePoints() {
        Evaluation ev = engine.evaluate(action("survey.completed", TUESDAY,
                JSON.createObjectNode().put("surveyId", "SRV-1")), silver(), List.of(survey()), zero);

        assertThat(ev.outcome()).isEqualTo(Evaluation.Outcome.MATCHED);
        assertThat(ev.effects()).hasSize(1);
        assertThat(ev.effects().get(0).amount()).isEqualTo(80);
        assertThat(ev.actionEffects()).hasSize(1);
        Evaluation.ActionEffect plays = ev.actionEffects().get(0);
        assertThat(plays.type()).isEqualTo("GRANT_PLAYS");
        assertThat(plays.params().path("contestCode").asString()).isEqualTo("IW-AUTUNNO");
        assertThat(plays.effectId()).isNotEqualTo(ev.effects().get(0).effectId());
        assertThat(ev.results().get(0).effects()).extracting(Evaluation.EffectResult::type)
                .containsExactly("GRANT_POINTS", "GRANT_PLAYS");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "eq         | 100  | 100  | true",
        "eq         | 100  | 50   | false",
        "neq        | 100  | 50   | true",
        "neq        | 100  | 100  | false",
        "gt         | 100  | 50   | true",
        "gt         | 100  | 100  | false",
        "gte        | 100  | 100  | true",
        "gte        | 100  | 150  | false",
        "lt         | 100  | 150  | true",
        "lt         | 100  | 50   | false",
        "lte        | 100  | 100  | true",
        "lte        | 100  | 50   | false",
        "in         | EUR  | [\"EUR\", \"USD\"] | true",
        "in         | GBP  | [\"EUR\", \"USD\"] | false",
        "nin        | GBP  | [\"EUR\", \"USD\"] | true",
        "nin        | EUR  | [\"EUR\", \"USD\"] | false",
        "contains   | elec | elec | true",
        "contains   | food | elec | false",
        "ncontains  | food | elec | true",
        "ncontains  | elec | elec | false",
        "between    | 100  | [50, 200] | true",
        "between    | 10   | [50, 200] | false",
        "startsWith | ABC  | ABC  | true",
        "startsWith | DEF  | ABC  | false",
    })
    void conditionOperatorsMatchAndReject(String cmpOp, String dataValue, String ruleValue, boolean expectedMatch) {
        String dataJson = dataValue.equals("100") || dataValue.equals("50") || dataValue.equals("10") || dataValue.equals("150")
            ? dataValue : "\"" + dataValue + "\"";

        String valueJson = ruleValue.startsWith("[") ? ruleValue :
            (ruleValue.equals("100") || ruleValue.equals("50") || ruleValue.equals("10") || ruleValue.equals("150") ? ruleValue : "\"" + ruleValue + "\"");

        JsonNode rules = node("[{\"field\":\"data.val\",\"cmp\":\"" + cmpOp + "\",\"value\":" + valueJson + "}]");

        Campaign cmp = campaign("OP-TEST", "Operators", 100, List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":" + rules.toString() + "}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":10,\"tierMultiplierApplies\":false}]",
                "{}");

        JsonNode data = node("{\"val\":" + dataJson + "}");

        EvalAction act = action("purchase.completed", TUESDAY, data);
        Evaluation ev = engine.evaluate(act, silver(), List.of(cmp), zero);

        if (expectedMatch) {
            assertThat(ev.outcome()).isEqualTo(Evaluation.Outcome.MATCHED);
        } else {
            assertThat(ev.outcome()).isEqualTo(Evaluation.Outcome.NO_MATCH);
            assertThat(ev.results().get(0).reason()).isEqualTo(Evaluation.SkipReason.CONDITION);
        }
    }

    @Test
    void conditionOperatorsExistsAndNexists() {
        Campaign cmpExists = campaign("EXISTS", "Exists", 100, List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":[{\"field\":\"data.val\",\"cmp\":\"exists\"}]}", "[]", "{}");
        Campaign cmpNexists = campaign("NEXISTS", "Nexists", 100, List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":[{\"field\":\"data.val\",\"cmp\":\"nexists\"}]}", "[]", "{}");

        EvalAction actWithVal = action("purchase.completed", TUESDAY, node("{\"val\":\"present\"}"));
        EvalAction actWithoutVal = action("purchase.completed", TUESDAY, node("{}"));

        // Exists true
        assertThat(engine.evaluate(actWithVal, silver(), List.of(cmpExists), zero).outcome()).isEqualTo(Evaluation.Outcome.MATCHED);
        // Exists false
        assertThat(engine.evaluate(actWithoutVal, silver(), List.of(cmpExists), zero).outcome()).isEqualTo(Evaluation.Outcome.NO_MATCH);

        // Nexists true
        assertThat(engine.evaluate(actWithoutVal, silver(), List.of(cmpNexists), zero).outcome()).isEqualTo(Evaluation.Outcome.MATCHED);
        // Nexists false
        assertThat(engine.evaluate(actWithVal, silver(), List.of(cmpNexists), zero).outcome()).isEqualTo(Evaluation.Outcome.NO_MATCH);
    }

    @Test
    void conditionOperatorsGroupAnyAndNot() {
        Campaign cmpAny = campaign("ANY", "Any", 100, List.of("purchase.completed"),
                "{\"op\":\"any\",\"rules\":[{\"field\":\"data.v\",\"cmp\":\"eq\",\"value\":1},{\"field\":\"data.v\",\"cmp\":\"eq\",\"value\":2}]}", "[]", "{}");
        Campaign cmpNot = campaign("NOT", "Not", 100, List.of("purchase.completed"),
                "{\"op\":\"not\",\"rules\":[{\"field\":\"data.v\",\"cmp\":\"eq\",\"value\":1}]}", "[]", "{}");

        EvalAction act1 = action("purchase.completed", TUESDAY, node("{\"v\":1}"));
        EvalAction act2 = action("purchase.completed", TUESDAY, node("{\"v\":2}"));
        EvalAction act3 = action("purchase.completed", TUESDAY, node("{\"v\":3}"));

        // Any true (matches first, matches second)
        assertThat(engine.evaluate(act1, silver(), List.of(cmpAny), zero).outcome()).isEqualTo(Evaluation.Outcome.MATCHED);
        assertThat(engine.evaluate(act2, silver(), List.of(cmpAny), zero).outcome()).isEqualTo(Evaluation.Outcome.MATCHED);
        // Any false
        assertThat(engine.evaluate(act3, silver(), List.of(cmpAny), zero).outcome()).isEqualTo(Evaluation.Outcome.NO_MATCH);

        // Not true (not equal to 1)
        assertThat(engine.evaluate(act2, silver(), List.of(cmpNot), zero).outcome()).isEqualTo(Evaluation.Outcome.MATCHED);
        // Not false (is equal to 1)
        assertThat(engine.evaluate(act1, silver(), List.of(cmpNot), zero).outcome()).isEqualTo(Evaluation.Outcome.NO_MATCH);
    }

    @Test
    void fieldLookupsResolveMemberContextHistory() {
        JsonNode rules = JSON.readTree("[ " +
                "{\"field\":\"member.tier\",\"cmp\":\"eq\",\"value\":\"SILVER\"}, " +
                "{\"field\":\"member.status\",\"cmp\":\"eq\",\"value\":\"ACTIVE\"}, " +
                "{\"field\":\"member.segments\",\"cmp\":\"contains\",\"value\":\"VIP\"}, " +
                "{\"field\":\"member.labels\",\"cmp\":\"contains\",\"value\":\"early-adopter\"}, " +
                "{\"field\":\"member.age\",\"cmp\":\"gte\",\"value\":18}, " +
                "{\"field\":\"member.registeredDaysAgo\",\"cmp\":\"gt\",\"value\":0}, " +
                "{\"field\":\"member.attributes.preferredStore\",\"cmp\":\"eq\",\"value\":\"Milan\"}, " +
                "{\"field\":\"context.source\",\"cmp\":\"eq\",\"value\":\"urn:loyaltyhub:source:ecommerce\"}, " +
                "{\"field\":\"context.dayOfWeek\",\"cmp\":\"eq\",\"value\":\"TUE\"}, " +
                "{\"field\":\"context.hour\",\"cmp\":\"eq\",\"value\":12}, " +
                "{\"field\":\"context.date\",\"cmp\":\"eq\",\"value\":\"2026-09-15\"}, " +
                "{\"field\":\"history.actionCount\",\"cmp\":\"eq\",\"value\":5}, " +
                "{\"field\":\"history.daysSinceLastAction\",\"cmp\":\"eq\",\"value\":2} " +
                "]");
        Campaign cmp = campaign("FIELD-TEST", "Fields", 100, List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":" + rules.toString() + "}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":10,\"tierMultiplierApplies\":false}]",
                "{}");

        Counters c = new Counters() {
            public int memberMatches(String c, String m, String p, String k) { return 0; }
            public long globalPointsDecided(String c) { return 0; }
            public long globalMatches(String c) { return 0; }
            public long historyActionCount(String m, String t) { return 5; }
            public long historyDaysSinceLastAction(String m, String t) { return 2; }
        };

        EvalAction act = action("purchase.completed", TUESDAY, JSON.createObjectNode());

        MemberSnapshot m = new MemberSnapshot("MBR-000003", "ACTIVE", "SILVER",
                List.of("VIP"), List.of("early-adopter"),
                JSON.createObjectNode().put("preferredStore", "Milan"),
                Instant.parse("2026-01-01T00:00:00Z"),
                java.time.LocalDate.parse("2000-01-01"));

        Evaluation ev = engine.evaluate(act, m, List.of(cmp), c);
        assertThat(ev.outcome()).isEqualTo(Evaluation.Outcome.MATCHED);

        // Negative cases for each namespace
        Campaign cmpNegMember = campaign("NEG-MEMBER", "Neg Member", 100, List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":[{\"field\":\"member.tier\",\"cmp\":\"eq\",\"value\":\"GOLD\"}]}", "[]", "{}");
        assertThat(engine.evaluate(act, m, List.of(cmpNegMember), c).outcome()).isEqualTo(Evaluation.Outcome.NO_MATCH);

        Campaign cmpNegContext = campaign("NEG-CONTEXT", "Neg Context", 100, List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":[{\"field\":\"context.dayOfWeek\",\"cmp\":\"eq\",\"value\":\"SUN\"}]}", "[]", "{}");
        assertThat(engine.evaluate(act, m, List.of(cmpNegContext), c).outcome()).isEqualTo(Evaluation.Outcome.NO_MATCH);

        Campaign cmpNegHistory = campaign("NEG-HISTORY", "Neg History", 100, List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":[{\"field\":\"history.actionCount\",\"cmp\":\"gt\",\"value\":10}]}", "[]", "{}");
        assertThat(engine.evaluate(act, m, List.of(cmpNegHistory), c).outcome()).isEqualTo(Evaluation.Outcome.NO_MATCH);
    }

    @Test
    void inactiveMemberIsNoMember() {
        MemberSnapshot blocked = new MemberSnapshot("MBR-000008", "BLOCKED", "BASE",
                List.of(), List.of(), JSON.createObjectNode(), Instant.parse("2020-01-01T00:00:00Z"), null);
        Evaluation ev = engine.evaluate(purchase(TUESDAY, 130), blocked, List.of(purchaseBase()), zero);
        assertThat(ev.outcome()).isEqualTo(Evaluation.Outcome.NO_MEMBER);
    }

    @Test
    void exclusiveGroupHighestPriorityWinsOthersSkipped() {
        Campaign cmpLow = new Campaign("id-CMP-LOW", "CMP-LOW", "Low Priority", null, null, null, List.of("purchase.completed"),
                JSON.createObjectNode().put("all", true), node("{\"op\":\"all\",\"rules\":[]}"),
                node("[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":10,\"tierMultiplierApplies\":false}]"),
                node("{}"), JSON.createObjectNode(), 10, "GROUP1", true, false, false, List.of(),
                CampaignStatus.LIVE, 0, null, null);

        Campaign cmpHigh = new Campaign("id-CMP-HIGH", "CMP-HIGH", "High Priority", null, null, null, List.of("purchase.completed"),
                JSON.createObjectNode().put("all", true), node("{\"op\":\"all\",\"rules\":[]}"),
                node("[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":20,\"tierMultiplierApplies\":false}]"),
                node("{}"), JSON.createObjectNode(), 100, "GROUP1", true, false, false, List.of(),
                CampaignStatus.LIVE, 0, null, null);

        Evaluation ev = engine.evaluate(purchase(TUESDAY, 50), silver(), List.of(cmpLow, cmpHigh), zero);

        assertThat(ev.outcome()).isEqualTo(Evaluation.Outcome.MATCHED);
        assertThat(ev.results()).hasSize(2);

        Evaluation.CampaignResult highRes = ev.results().stream().filter(r -> r.campaignCode().equals("CMP-HIGH")).findFirst().get();
        Evaluation.CampaignResult lowRes = ev.results().stream().filter(r -> r.campaignCode().equals("CMP-LOW")).findFirst().get();

        assertThat(highRes.matched()).isTrue();
        assertThat(lowRes.matched()).isFalse();
        assertThat(lowRes.reason()).isEqualTo(Evaluation.SkipReason.EXCLUSIVE);

        assertThat(ev.effects()).hasSize(1);
        assertThat(grant(ev, "PTS").amount()).isEqualTo(20);
    }

    @Test
    void exclusiveGroupWithLookupsAndMultipliers_HigherPriorityWins() {
        Campaign cmpHighLookup = campaign("CMP-HIGH-LOOKUP", "High Priority Lookup", 200, List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":[]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"LOOKUP\",\"amountField\":\"data.tierCode\",\"lookup\":{\"SILVER\": 200, \"GOLD\": 500},\"tierMultiplierApplies\":false}]",
                "{}");
        cmpHighLookup = new Campaign("id-CMP-HIGH-LOOKUP", cmpHighLookup.code(), cmpHighLookup.name(), null, null, null, List.of("purchase.completed"),
                cmpHighLookup.audience(), cmpHighLookup.conditions(), cmpHighLookup.effects(), cmpHighLookup.limits(),
                JSON.createObjectNode(), 200, "EXCLUSIVE_GRP", true, false, false, List.of(),
                CampaignStatus.LIVE, 0, null, null);

        Campaign cmpLowFixed = campaign("CMP-LOW-FIXED", "Low Priority Fixed", 100, List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":[]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":100,\"tierMultiplierApplies\":false}]",
                "{}");
        cmpLowFixed = new Campaign("id-CMP-LOW-FIXED", cmpLowFixed.code(), cmpLowFixed.name(), null, null, null, List.of("purchase.completed"),
                cmpLowFixed.audience(), cmpLowFixed.conditions(), cmpLowFixed.effects(), cmpLowFixed.limits(),
                JSON.createObjectNode(), 100, "EXCLUSIVE_GRP", true, false, false, List.of(),
                CampaignStatus.LIVE, 0, null, null);

        Evaluation ev = engine.evaluate(action("purchase.completed", TUESDAY, JSON.createObjectNode().put("tierCode", "SILVER")), silver(), List.of(cmpHighLookup, cmpLowFixed), zero);

        assertThat(ev.outcome()).isEqualTo(Evaluation.Outcome.MATCHED);
        assertThat(ev.results()).hasSize(2);

        Evaluation.CampaignResult lowRes = ev.results().stream().filter(r -> r.campaignCode().equals("CMP-LOW-FIXED")).findFirst().get();
        assertThat(lowRes.matched()).isFalse();
        assertThat(lowRes.reason()).isEqualTo(Evaluation.SkipReason.EXCLUSIVE);

        assertThat(ev.effects()).hasSize(1);
        assertThat(grant(ev, "PTS").amount()).isEqualTo(200); // Only high priority lookup grant applied
    }

    @Test
    void grantPointsPerAmountWithUnitStepAndRounding() {
        Campaign floorCmp = campaign("CMP-FLOOR", "Floor", 100, List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":[]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"PER_AMOUNT\",\"amountField\":\"data.amount\",\"value\":5,\"unitStep\":2.5,\"rounding\":\"FLOOR\",\"tierMultiplierApplies\":false}]",
                "{}");

        Campaign ceilCmp = campaign("CMP-CEIL", "Ceil", 100, List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":[]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"PER_AMOUNT\",\"amountField\":\"data.amount\",\"value\":5,\"unitStep\":2.5,\"rounding\":\"CEIL\",\"tierMultiplierApplies\":false}]",
                "{}");

        Campaign roundCmp = campaign("CMP-ROUND", "Round", 100, List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":[]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"PER_AMOUNT\",\"amountField\":\"data.amount\",\"value\":5,\"unitStep\":2.5,\"rounding\":\"ROUND\",\"tierMultiplierApplies\":false}]",
                "{}");

        // Amount: 6.5
        // Units: 6.5 / 2.5 = 2.6
        // FLOOR: 2 * 5 = 10
        // CEIL: 3 * 5 = 15
        // ROUND: 3 * 5 = 15

        Evaluation evFloor = engine.evaluate(purchase(TUESDAY, 6.5), silver(), List.of(floorCmp), zero);
        assertThat(grant(evFloor, "PTS").amount()).isEqualTo(10);

        Evaluation evCeil = engine.evaluate(purchase(TUESDAY, 6.5), silver(), List.of(ceilCmp), zero);
        assertThat(grant(evCeil, "PTS").amount()).isEqualTo(15);

        Evaluation evRound = engine.evaluate(purchase(TUESDAY, 6.5), silver(), List.of(roundCmp), zero);
        assertThat(grant(evRound, "PTS").amount()).isEqualTo(15);
    }

    @Test
    void grantPointsPerAmountWithMinMax() {
        Campaign cmp = campaign("CMP-MINMAX", "MinMax", 100, List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":[]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"PER_AMOUNT\",\"amountField\":\"data.amount\",\"value\":1,\"unitStep\":1,\"rounding\":\"FLOOR\",\"min\":10,\"max\":50,\"tierMultiplierApplies\":false}]",
                "{}");

        Evaluation evLow = engine.evaluate(purchase(TUESDAY, 5.0), silver(), List.of(cmp), zero);
        assertThat(grant(evLow, "PTS").amount()).isEqualTo(10); // min kicks in

        Evaluation evMid = engine.evaluate(purchase(TUESDAY, 25.0), silver(), List.of(cmp), zero);
        assertThat(grant(evMid, "PTS").amount()).isEqualTo(25); // normal

        Evaluation evHigh = engine.evaluate(purchase(TUESDAY, 100.0), silver(), List.of(cmp), zero);
        assertThat(grant(evHigh, "PTS").amount()).isEqualTo(50); // max kicks in
    }

    @Test
    void grantPointsAppliesTierMultiplierWhenFlagIsTrue() {
        Campaign cmp = campaign("CMP-TIER", "Tier Multiplier", 100, List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":[]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":10,\"tierMultiplierApplies\":true}]",
                "{}");

        // The tier multiplier factor logic happens in the wallet, but the Engine creates a GrantedEffect
        // with tierMultiplierApplies = true so the downstream component can know.
        Evaluation ev = engine.evaluate(purchase(TUESDAY, 50.0), silver(), List.of(cmp), zero);

        assertThat(ev.outcome()).isEqualTo(Evaluation.Outcome.MATCHED);
        assertThat(grant(ev, "PTS").tierMultiplierApplies()).isTrue();
    }

    @Test
    void grantPointsFromField() {
        Campaign cmp = campaign("CMP-FROM-FIELD", "From Field", 100, List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":[]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FROM_FIELD\",\"amountField\":\"data.amount\",\"tierMultiplierApplies\":false}]",
                "{}");

        Evaluation evHit = engine.evaluate(action("purchase.completed", TUESDAY, JSON.createObjectNode().put("amount", 100)), silver(), List.of(cmp), zero);
        assertThat(grant(evHit, "PTS").amount()).isEqualTo(100);

        // Missing field (data.missing)
        Campaign cmpMissing = campaign("CMP-FROM-FIELD-MISS", "From Field Missing", 100, List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":[]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FROM_FIELD\",\"amountField\":\"data.missing\",\"tierMultiplierApplies\":false}]",
                "{}");
        Evaluation evMissing = engine.evaluate(purchase(TUESDAY, 100.0), silver(), List.of(cmpMissing), zero);
        assertThat(evMissing.outcome()).isEqualTo(Evaluation.Outcome.MATCHED);
        assertThat(evMissing.effects()).isEmpty();

        // Decimal field (amount is 6.5) -> discarded
        Evaluation evDecimal = engine.evaluate(purchase(TUESDAY, 6.5), silver(), List.of(cmp), zero);
        assertThat(evDecimal.outcome()).isEqualTo(Evaluation.Outcome.MATCHED);
        assertThat(evDecimal.effects()).isEmpty();

        // String field -> discarded
        Evaluation evString = engine.evaluate(action("purchase.completed", TUESDAY, JSON.createObjectNode().put("amount", "100")), silver(), List.of(cmp), zero);
        assertThat(evString.outcome()).isEqualTo(Evaluation.Outcome.MATCHED);
        assertThat(evString.effects()).isEmpty();
    }

    @Test
    void grantPointsLookup() {
        Campaign cmp = campaign("CMP-LOOKUP", "Lookup", 100, List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":[]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"LOOKUP\",\"amountField\":\"data.tierCode\",\"lookup\":{\"SILVER\": 200, \"GOLD\": 500},\"tierMultiplierApplies\":false}]",
                "{}");

        // Hit (GOLD -> 500)
        Evaluation evHit = engine.evaluate(action("purchase.completed", TUESDAY, JSON.createObjectNode().put("tierCode", "GOLD")), silver(), List.of(cmp), zero);
        assertThat(grant(evHit, "PTS").amount()).isEqualTo(500);

        // Miss (BASE -> discarded)
        Evaluation evMiss = engine.evaluate(action("purchase.completed", TUESDAY, JSON.createObjectNode().put("tierCode", "BASE")), silver(), List.of(cmp), zero);
        assertThat(evMiss.outcome()).isEqualTo(Evaluation.Outcome.MATCHED);
        assertThat(evMiss.effects()).isEmpty();

        // Numeric key lookup
        Campaign cmpNum = campaign("CMP-LOOKUP-NUM", "Lookup Num", 100, List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":[]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"LOOKUP\",\"amountField\":\"data.level\",\"lookup\":{\"2\": 100, \"3\": 300},\"tierMultiplierApplies\":false}]",
                "{}");
        Evaluation evNum = engine.evaluate(action("purchase.completed", TUESDAY, JSON.createObjectNode().put("level", 2)), silver(), List.of(cmpNum), zero);
        assertThat(grant(evNum, "PTS").amount()).isEqualTo(100);
    }

    @Test
    void grantPointsLimitsAppliedToFromFieldAndLookup() {
        Campaign cmpFromField = campaign("CMP-FF-LIMITS", "From Field Limits", 100, List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":[]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FROM_FIELD\",\"amountField\":\"data.amount\",\"min\":10,\"max\":50,\"tierMultiplierApplies\":false}]",
                "{}");
        // Low -> min
        Evaluation evLowFF = engine.evaluate(action("purchase.completed", TUESDAY, JSON.createObjectNode().put("amount", 5)), silver(), List.of(cmpFromField), zero);
        assertThat(grant(evLowFF, "PTS").amount()).isEqualTo(10);
        // High -> max
        Evaluation evHighFF = engine.evaluate(action("purchase.completed", TUESDAY, JSON.createObjectNode().put("amount", 100)), silver(), List.of(cmpFromField), zero);
        assertThat(grant(evHighFF, "PTS").amount()).isEqualTo(50);

        Campaign cmpLookup = campaign("CMP-LK-LIMITS", "Lookup Limits", 100, List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":[]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"LOOKUP\",\"amountField\":\"data.tierCode\",\"lookup\":{\"SILVER\": 5, \"GOLD\": 100},\"min\":10,\"max\":50,\"tierMultiplierApplies\":false}]",
                "{}");
        // Low -> min
        Evaluation evLowLK = engine.evaluate(action("purchase.completed", TUESDAY, JSON.createObjectNode().put("tierCode", "SILVER")), silver(), List.of(cmpLookup), zero);
        assertThat(grant(evLowLK, "PTS").amount()).isEqualTo(10);
        // High -> max
        Evaluation evHighLK = engine.evaluate(action("purchase.completed", TUESDAY, JSON.createObjectNode().put("tierCode", "GOLD")), silver(), List.of(cmpLookup), zero);
        assertThat(grant(evHighLK, "PTS").amount()).isEqualTo(50);
    }

    @Test
    void lookupGrantCombinedWithMultiplier() {
        Campaign cmpLookup = campaign("CMP-LOOKUP-MULT", "Lookup Mult", 100, List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":[]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"LOOKUP\",\"amountField\":\"data.tierCode\",\"lookup\":{\"GOLD\": 500},\"tierMultiplierApplies\":false}]",
                "{}");

        Evaluation ev = engine.evaluate(action("purchase.completed", TUESDAY, JSON.createObjectNode().put("tierCode", "GOLD")), silver(), List.of(cmpLookup, weekendX2()), zero);
        // TUESDAY doesn't apply weekendX2 multiplier, let's test SATURDAY
        Evaluation evSat = engine.evaluate(action("purchase.completed", SATURDAY, JSON.createObjectNode().put("tierCode", "GOLD")), silver(), List.of(cmpLookup, weekendX2()), zero);

        assertThat(grant(evSat, "PTS").amount()).isEqualTo(1000); // 500 * 2
    }

    @Test
    void historyActionCountAndDaysSinceLastActionConditions() {
        Counters testCounters = new Counters() {
            public int memberMatches(String c, String m, String p, String k) { return 0; }
            public long globalPointsDecided(String c) { return 0; }
            public long globalMatches(String c) { return 0; }
            public long historyActionCount(String m, String t) { return 10; }
            public long historyDaysSinceLastAction(String m, String t) { return 15; }
        };

        Campaign cmpHist = campaign("CMP-HIST", "History Test", 100, List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":[{\"field\":\"history.actionCount\",\"cmp\":\"gte\",\"value\":10}, {\"field\":\"history.daysSinceLastAction\",\"cmp\":\"eq\",\"value\":15}]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":50,\"tierMultiplierApplies\":false}]",
                "{}");

        Evaluation evHit = engine.evaluate(purchase(TUESDAY, 100.0), silver(), List.of(cmpHist), testCounters);
        assertThat(evHit.outcome()).isEqualTo(Evaluation.Outcome.MATCHED);
        assertThat(grant(evHit, "PTS").amount()).isEqualTo(50);

        Campaign cmpHistFail = campaign("CMP-HIST-FAIL", "History Fail Test", 100, List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":[{\"field\":\"history.actionCount\",\"cmp\":\"gte\",\"value\":15}]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":50,\"tierMultiplierApplies\":false}]",
                "{}");
        Evaluation evMiss = engine.evaluate(purchase(TUESDAY, 100.0), silver(), List.of(cmpHistFail), testCounters);
        assertThat(evMiss.outcome()).isEqualTo(Evaluation.Outcome.NO_MATCH);
    }

    @Test
    void engineEvaluatesSeedCampaignTierUpgraded() {
        Campaign cmpTierUpBonus = campaign("CMP-TIER-UP-BONUS", "Bonus di livello", 100, List.of("tier.upgraded"),
                "{\"op\":\"all\",\"rules\":[]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"LOOKUP\",\"amountField\":\"data.newTier\",\"lookup\":{\"SILVER\":200,\"GOLD\":500,\"PLATINUM\":1000},\"tierMultiplierApplies\":false}]",
                "{}");

        Evaluation ev = engine.evaluate(action("tier.upgraded", TUESDAY, JSON.createObjectNode().put("newTier", "GOLD")), silver(), List.of(cmpTierUpBonus), zero);

        assertThat(ev.outcome()).isEqualTo(Evaluation.Outcome.MATCHED);
        GrantedEffect effect = grant(ev, "PTS");
        assertThat(effect.amount()).isEqualTo(500);
        assertThat(effect.tierMultiplierApplies()).isFalse();
    }

    @Test
    void emptyOrMalformedInputs() {
        Campaign cmp = campaign("CMP-EMPTY", "Empty", 100, List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":[]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"PER_AMOUNT\",\"amountField\":\"data.missing\",\"value\":1,\"tierMultiplierApplies\":false}]",
                "{}");

        // missing field will result in computeBase returning null -> no grant
        Evaluation ev = engine.evaluate(purchase(TUESDAY, 100.0), silver(), List.of(cmp), zero);
        assertThat(ev.outcome()).isEqualTo(Evaluation.Outcome.MATCHED);
        assertThat(ev.effects()).isEmpty();
    }

    @Test
    void limitsReachedGlobalBudget() {
        Counters reachedGlobal = new Counters() {
            public int memberMatches(String c, String m, String p, String k) { return 0; }
            public long globalPointsDecided(String c) { return 5000; } // maxPoints = 5000
            public long globalMatches(String c) { return 10; }
            public long historyActionCount(String m, String t) { return 0; }
            public long historyDaysSinceLastAction(String m, String t) { return -1; }
        };

        Campaign cmp = campaign("CMP-BUDGET", "Budget", 100, List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":[]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":10,\"tierMultiplierApplies\":false}]",
                "{\"global\":{\"maxPoints\":5000}}");

        Evaluation ev = engine.evaluate(purchase(TUESDAY, 100.0), silver(), List.of(cmp), reachedGlobal);
        assertThat(ev.outcome()).isEqualTo(Evaluation.Outcome.NO_MATCH);
        assertThat(ev.results().get(0).reason()).isEqualTo(Evaluation.SkipReason.BUDGET);

        Counters reachedMatches = new Counters() {
            public int memberMatches(String c, String m, String p, String k) { return 0; }
            public long globalPointsDecided(String c) { return 0; }
            public long globalMatches(String c) { return 100; } // maxMatches = 100
            public long historyActionCount(String m, String t) { return 0; }
            public long historyDaysSinceLastAction(String m, String t) { return -1; }
        };

        Campaign cmp2 = campaign("CMP-BUDGET2", "Budget2", 100, List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":[]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":10,\"tierMultiplierApplies\":false}]",
                "{\"global\":{\"maxMatches\":100}}");

        Evaluation ev2 = engine.evaluate(purchase(TUESDAY, 100.0), silver(), List.of(cmp2), reachedMatches);
        assertThat(ev2.outcome()).isEqualTo(Evaluation.Outcome.NO_MATCH);
        assertThat(ev2.results().get(0).reason()).isEqualTo(Evaluation.SkipReason.BUDGET);
    }

    @Test
    void limitsPeriodKeys() {
        Campaign cmp = campaign("CMP-PERIOD", "Period", 100, List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":[]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":10,\"tierMultiplierApplies\":false}]",
                "{\"perMember\":[{\"max\":1,\"period\":\"MONTH\"}]}");

        Counters monthLimit = new Counters() {
            public int memberMatches(String c, String m, String p, String k) {
                if ("MONTH".equals(p) && "2026-09".equals(k)) {
                    return 1;
                }
                return 0;
            }
            public long globalPointsDecided(String c) { return 0; }
            public long globalMatches(String c) { return 0; }
            public long historyActionCount(String m, String t) { return 0; }
            public long historyDaysSinceLastAction(String m, String t) { return -1; }
        };

        Evaluation ev = engine.evaluate(purchase(TUESDAY, 100.0), silver(), List.of(cmp), monthLimit);
        assertThat(ev.outcome()).isEqualTo(Evaluation.Outcome.NO_MATCH);
        assertThat(ev.results().get(0).reason()).isEqualTo(Evaluation.SkipReason.LIMIT);
    }

    @Test
    void checkScheduleRules() {
        Campaign cmpOutTime = new Campaign("id-CMP-SCHED", "CMP-SCHED", "Schedule", null, null, null, List.of("purchase.completed"),
                JSON.createObjectNode().put("all", true), node("{\"op\":\"all\",\"rules\":[]}"),
                node("[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":10,\"tierMultiplierApplies\":false}]"),
                node("{}"), node("{\"hours\":[14, 18]}"), 100, null, true, false, false, List.of(),
                CampaignStatus.LIVE, 0, null, null);

        // TUESDAY is at 10:00:00Z -> 12:00:00 CEST. Hours allowed are 14-18, so it will fail schedule check.
        Evaluation evOutTime = engine.evaluate(purchase(TUESDAY, 100.0), silver(), List.of(cmpOutTime), zero);
        assertThat(evOutTime.outcome()).isEqualTo(Evaluation.Outcome.NO_MATCH);
        assertThat(evOutTime.results().get(0).reason()).isEqualTo(Evaluation.SkipReason.NOT_IN_SCHEDULE);

        Campaign cmpOutDay = new Campaign("id-CMP-SCHED2", "CMP-SCHED2", "Schedule2", null, null, null, List.of("purchase.completed"),
                JSON.createObjectNode().put("all", true), node("{\"op\":\"all\",\"rules\":[]}"),
                node("[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":10,\"tierMultiplierApplies\":false}]"),
                node("{}"), node("{\"daysOfWeek\":[\"SAT\", \"SUN\"]}"), 100, null, true, false, false, List.of(),
                CampaignStatus.LIVE, 0, null, null);

        // TUESDAY is not SAT or SUN.
        Evaluation evOutDay = engine.evaluate(purchase(TUESDAY, 100.0), silver(), List.of(cmpOutDay), zero);
        assertThat(evOutDay.outcome()).isEqualTo(Evaluation.Outcome.NO_MATCH);
        assertThat(evOutDay.results().get(0).reason()).isEqualTo(Evaluation.SkipReason.NOT_IN_SCHEDULE);
    }

    @Test
    void checkAudienceRules() {
        Campaign cmpAudience = new Campaign("id-CMP-AUD", "CMP-AUD", "Audience", null, null, null, List.of("purchase.completed"),
                node("{\"tiers\":[\"GOLD\"]}"), node("{\"op\":\"all\",\"rules\":[]}"),
                node("[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":10,\"tierMultiplierApplies\":false}]"),
                node("{}"), JSON.createObjectNode(), 100, null, true, false, false, List.of(),
                CampaignStatus.LIVE, 0, null, null);

        // Silver is not Gold.
        Evaluation ev = engine.evaluate(purchase(TUESDAY, 100.0), silver(), List.of(cmpAudience), zero);
        assertThat(ev.outcome()).isEqualTo(Evaluation.Outcome.NO_MATCH);
        assertThat(ev.results().get(0).reason()).isEqualTo(Evaluation.SkipReason.AUDIENCE);
    }

    // ---------- fixture ----------

    private GrantedEffect grant(Evaluation ev, String currency) {
        return ev.effects().stream().filter(g -> g.currency().equals(currency)).findFirst().orElseThrow();
    }

    private EvalAction purchase(Instant t, double amount) {
        return action("purchase.completed", t, JSON.createObjectNode().put("amount", amount).put("currency", "EUR"));
    }

    private EvalAction action(String type, Instant t, JsonNode data) {
        return new EvalAction("01ACT" + type + t.toEpochMilli(), type, "MBR-000003",
                "urn:loyaltyhub:source:ecommerce", t, data);
    }

    private MemberSnapshot silver() {
        return new MemberSnapshot("MBR-000003", "ACTIVE", "SILVER", List.of(), List.of(),
                JSON.createObjectNode(), Instant.parse("2024-01-01T00:00:00Z"), null);
    }

    private Campaign purchaseBase() {
        return campaign("CMP-PURCHASE-BASE", "Punti sugli acquisti", 100, List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":[{\"field\":\"data.amount\",\"cmp\":\"gte\",\"value\":1}]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"PER_AMOUNT\",\"amountField\":\"data.amount\",\"value\":1,\"unitStep\":1,\"rounding\":\"FLOOR\",\"tierMultiplierApplies\":true},"
                        + "{\"type\":\"GRANT_POINTS\",\"currency\":\"STS\",\"mode\":\"PER_AMOUNT\",\"amountField\":\"data.amount\",\"value\":1,\"unitStep\":1,\"rounding\":\"FLOOR\",\"tierMultiplierApplies\":false}]",
                "{\"perMember\":[{\"max\":3,\"period\":\"DAY\"}]}");
    }

    private Campaign weekendX2() {
        return campaign("CMP-WEEKEND-X2", "Weekend x2", 200, List.of("purchase.completed"),
                "{\"op\":\"all\",\"rules\":[{\"field\":\"context.dayOfWeek\",\"cmp\":\"in\",\"value\":[\"SAT\",\"SUN\"]}]}",
                "[{\"type\":\"MULTIPLIER\",\"currency\":\"PTS\",\"factor\":2,\"scope\":\"ALL_GRANTS\"}]",
                "{}");
    }

    private Campaign appDaily() {
        return campaign("CMP-APP-DAILY", "Accesso", 100, List.of("app.login.daily"),
                "{\"op\":\"all\",\"rules\":[]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":5,\"tierMultiplierApplies\":false}]",
                "{\"perMember\":[{\"max\":1,\"period\":\"DAY\"}]}");
    }

    private Campaign survey() {
        return campaign("CMP-SURVEY", "Survey", 100, List.of("survey.completed"),
                "{\"op\":\"all\",\"rules\":[]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":80,\"tierMultiplierApplies\":true},"
                        + "{\"type\":\"GRANT_PLAYS\",\"contestCode\":\"IW-AUTUNNO\",\"count\":1}]",
                "{}");
    }

    private Campaign iwPoints() {
        return campaign("CMP-IW-PRIZE-POINTS", "Vincita in punti", 100, List.of("instantwin.won"),
                "{\"op\":\"all\",\"rules\":[{\"field\":\"data.prizeType\",\"cmp\":\"eq\",\"value\":\"POINTS\"}]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FROM_FIELD\",\"amountField\":\"data.points\",\"tierMultiplierApplies\":false}]",
                "{}");
    }

    private Campaign iwCoupon() {
        return campaign("CMP-IW-PRIZE-COUPON", "Vincita in coupon", 100, List.of("instantwin.won"),
                "{\"op\":\"all\",\"rules\":[{\"field\":\"data.prizeType\",\"cmp\":\"eq\",\"value\":\"COUPON\"}]}",
                "[{\"type\":\"ISSUE_COUPON\",\"rewardCodeField\":\"data.rewardCode\"}]",
                "{}");
    }

    private Campaign birthday() {
        return campaign("CMP-BIRTHDAY", "Compleanno", 100, List.of("member.birthday"),
                "{\"op\":\"all\",\"rules\":[]}",
                "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":250,\"tierMultiplierApplies\":true},"
                        + "{\"type\":\"SEND_MESSAGE\",\"templateCode\":\"MSG-BIRTHDAY\"}]",
                "{}");
    }

    private Campaign campaign(String code, String name, int priority, List<String> triggers,
                              String conditions, String effects, String limits) {
        return new Campaign("id-" + code, code, name, null, null, null, triggers,
                JSON.createObjectNode().put("all", true), node(conditions), node(effects), node(limits),
                JSON.createObjectNode(), priority, null, true, false, false, List.of(),
                CampaignStatus.LIVE, 0, null, null);
    }

    private JsonNode node(String json) {
        return JSON.readTree(json);
    }
}
