package io.loyaltyhub.campaign.engine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.campaign.domain.Campaign;
import io.loyaltyhub.campaign.domain.CampaignStatus;
import org.junit.jupiter.api.Test;

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
        Evaluation ev = engine.evaluate(action("survey.completed", TUESDAY,
                JSON.createObjectNode().put("surveyId", "SRV-1")), silver(), List.of(survey()), zero);

        assertThat(ev.effects()).isEmpty();
        assertThat(ev.results().get(0).reason()).isEqualTo(Evaluation.SkipReason.EFFECT_NOT_SUPPORTED_YET);
    }

    @Test
    void inactiveMemberIsNoMember() {
        MemberSnapshot blocked = new MemberSnapshot("MBR-000008", "BLOCKED", "BASE",
                List.of(), List.of(), JSON.createObjectNode(), Instant.parse("2020-01-01T00:00:00Z"), null);
        Evaluation ev = engine.evaluate(purchase(TUESDAY, 130), blocked, List.of(purchaseBase()), zero);
        assertThat(ev.outcome()).isEqualTo(Evaluation.Outcome.NO_MEMBER);
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
