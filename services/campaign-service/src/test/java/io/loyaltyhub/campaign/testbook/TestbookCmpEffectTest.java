package io.loyaltyhub.campaign.testbook;

import io.loyaltyhub.campaign.domain.Campaign;
import io.loyaltyhub.campaign.engine.CampaignEngine;
import io.loyaltyhub.campaign.engine.Evaluation;
import io.loyaltyhub.campaign.engine.GrantedEffect;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.StubCounters;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.TUESDAY;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.TYPE;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.action;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.campaign;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.engine;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.json;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.list;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.outcomeOf;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.silver;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-CMP §6 (docs/testbook/TB-CMP-campagne.md): effetti della campagna (docs/03 §3.4, F-CMP-04), moltiplicatori
 * (docs/03 §3.5 passo 3, F-CMP-07) ed {@code effectId} idempotente (passo 4). Motore puro.
 */
class TestbookCmpEffectTest {

    /**
     * TB-CMP-PTS: GRANT_POINTS FIXED / PER_AMOUNT / FROM_FIELD / LOOKUP con min/max e arrotondamenti ai limiti.
     * TESTBOOK: ambiguo, vedi TB-CMP-PTS-011 (mode assente), PTS-019 (ROUND sulla metà esatta), PTS-033 (unitStep 0),
     * PTS-034 (arrotondamento sconosciuto): si asserisce il comportamento attuale. PTS-039 segue Q-44.
     */
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/cmp/points.csv", numLinesToSkip = 1, delimiter = '|', quoteCharacter = '`')
    void grantPoints(String id, String description, String effect, String data, String expected, String currency,
                     String tierMultiplier, String pendingDays) {
        Campaign c = campaign("CMP-TB-PTS", 100, List.of(TYPE), null, null, "[" + effect + "]", null, null, null, null);

        Evaluation ev = engine().evaluate(action(TYPE, TUESDAY, data), silver(), List.of(c), StubCounters.zero());

        switch (expected) {
            case "UNSUPPORTED" -> {
                assertThat(outcomeOf(ev, "CMP-TB-PTS")).as(id).isEqualTo("EFFECT_NOT_SUPPORTED_YET");
                assertThat(ev.effects()).as(id).isEmpty();
            }
            case "NONE" -> {
                assertThat(outcomeOf(ev, "CMP-TB-PTS")).as(id + " la campagna scatta").isEqualTo("MATCHED");
                assertThat(ev.effects()).as(id + " effetto ≤ 0 scartato").isEmpty();
            }
            default -> {
                assertThat(ev.effects()).as(id).hasSize(1);
                GrantedEffect g = ev.effects().get(0);
                assertThat(g.amount()).as(id + " punti").isEqualTo(Long.parseLong(expected));
                if (currency != null) {
                    assertThat(g.currency()).as(id + " valuta").isEqualTo(currency);
                }
                if (tierMultiplier != null) {
                    assertThat(g.tierMultiplierApplies()).as(id + " tierMultiplierApplies").isEqualTo(Boolean.parseBoolean(tierMultiplier));
                }
                if (pendingDays != null) {
                    assertThat(g.pendingDays()).as(id + " pendingDays").isEqualTo(Integer.parseInt(pendingDays));
                }
            }
        }
    }

    /** TB-CMP-MUL: MULTIPLIER sulle altre campagne, stessa valuta, prodotto con tetto, arrotondamento per difetto. */
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/cmp/multiplier.csv", numLinesToSkip = 1, delimiter = '|', quoteCharacter = '`')
    void multiplier(String id, String description, long base, String labelsG1, String m1Effect, int prioM1, String m1State,
                    String m2Effect, String variant, int cap, String expPts, String expSts, String expFactor, String expG2) {
        String grants = "{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":" + base + "},"
                + "{\"type\":\"GRANT_POINTS\",\"currency\":\"STS\",\"mode\":\"FIXED\",\"value\":130}";
        boolean own = "OWN".equals(variant);
        List<Campaign> campaigns = new ArrayList<>();
        campaigns.add(campaign("CMP-TB-G1", 100, List.of(TYPE), null, null,
                "[" + grants + (own ? "," + m1Effect : "") + "]",
                "G1LIMIT".equals(variant) ? "{\"perMember\":[{\"max\":1,\"period\":\"ALWAYS\"}]}" : null,
                null, null, list(labelsG1)));
        if (!own) {
            campaigns.add(campaign("CMP-TB-M1", prioM1, List.of(TYPE), null,
                    "COND".equals(m1State) ? "{\"op\":\"all\",\"rules\":[{\"field\":\"data.channel\",\"cmp\":\"eq\",\"value\":\"WEB\"}]}" : null,
                    "[" + m1Effect + "]",
                    "LIMIT".equals(m1State) ? "{\"perMember\":[{\"max\":1,\"period\":\"ALWAYS\"}]}" : null,
                    null, null, null));
        }
        if (m2Effect != null) {
            campaigns.add(campaign("CMP-TB-M2", 150, List.of(TYPE), null, null, "[" + m2Effect + "]", null, null, null, null));
        }
        if ("G2".equals(variant)) {
            campaigns.add(campaign("CMP-TB-G2", 90, List.of(TYPE), null, null,
                    "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":50}]", null, null, null, null));
        }
        // Un match già registrato nel periodo ALWAYS: vale per la sola campagna che ha il limite 1/ALWAYS.
        StubCounters counters = StubCounters.of("ALWAYS:ALWAYS=1", 0L, 0L);

        Evaluation ev = new CampaignEngine(cap).evaluate(action(TYPE, TUESDAY, "{\"channel\":\"APP\"}"), silver(), campaigns, counters);

        GrantedEffect pts = grant(ev, "CMP-TB-G1", "PTS");
        GrantedEffect sts = grant(ev, "CMP-TB-G1", "STS");
        if ("NONE".equals(expPts)) {
            assertThat(pts).as(id + " nessun accredito PTS").isNull();
            assertThat(outcomeOf(ev, "CMP-TB-M1")).as(id + " il moltiplicatore scatta comunque").isEqualTo("MATCHED");
        } else {
            assertThat(pts.amount()).as(id + " PTS").isEqualTo(Long.parseLong(expPts));
            assertThat(pts.baseAmount()).as(id + " baseAmount invariato").isEqualTo(base);
        }
        if ("NONE".equals(expSts)) {
            assertThat(sts).as(id + " nessun accredito STS").isNull();
        } else {
            assertThat(sts.amount()).as(id + " STS").isEqualTo(Long.parseLong(expSts));
        }
        if (expFactor != null) {
            assertThat(pts.campaignMultiplier()).as(id + " fattore complessivo").isEqualTo(Double.parseDouble(expFactor));
        }
        if (expG2 != null) {
            assertThat(grant(ev, "CMP-TB-G2", "PTS").amount()).as(id + " PTS di G2").isEqualTo(Long.parseLong(expG2));
        }
    }

    /**
     * TB-CMP-OEF: effetti non monetari e scarto EFFECT_NOT_SUPPORTED_YET (docs/12 M1.3, Q-73).
     * TESTBOOK: ambiguo, vedi TB-CMP-OEF-002, OEF-003, OEF-004, OEF-007, OEF-008, OEF-010, OEF-017, OEF-019 (parametri
     * mancanti o fuori forma non trattati da docs/03 §3.4): si asserisce il comportamento attuale.
     */
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/cmp/other-effects.csv", numLinesToSkip = 1, delimiter = '|', quoteCharacter = '`')
    void otherEffects(String id, String description, String effects, String data, String expected, String params, String expPts) {
        Campaign c = campaign("CMP-TB-OEF", 100, List.of(TYPE), null, null, effects, null, null, null, null);

        Evaluation ev = engine().evaluate(action(TYPE, TUESDAY, data), silver(), List.of(c), StubCounters.zero());

        if ("UNSUPPORTED".equals(expected)) {
            assertThat(outcomeOf(ev, "CMP-TB-OEF")).as(id).isEqualTo("EFFECT_NOT_SUPPORTED_YET");
            assertThat(ev.actionEffects()).as(id + " nessun effetto").isEmpty();
        } else if ("MATCHED_NONE".equals(expected)) {
            assertThat(outcomeOf(ev, "CMP-TB-OEF")).as(id).isEqualTo("MATCHED");
            assertThat(ev.actionEffects()).as(id + " nessun effetto non monetario").isEmpty();
        } else {
            assertThat(outcomeOf(ev, "CMP-TB-OEF")).as(id).isEqualTo("MATCHED");
            String[] e = expected.split(":");
            assertThat(ev.actionEffects()).as(id).hasSize(1);
            Evaluation.ActionEffect a = ev.actionEffects().get(0);
            switch (e[0]) {
                case "PLAYS" -> {
                    assertThat(a.type()).as(id).isEqualTo("GRANT_PLAYS");
                    assertThat(a.params().path("contestCode").asString()).as(id).isEqualTo(e[1]);
                    assertThat(a.params().path("count").asInt(1)).as(id + " giocate").isEqualTo(Integer.parseInt(e[2]));
                    assertThat(explained(ev, "CMP-TB-OEF", "GRANT_PLAYS").amount()).as(id + " spiegabilità")
                            .isEqualTo(Long.parseLong(e[2]));
                }
                case "COUPON" -> {
                    assertThat(a.type()).as(id).isEqualTo("ISSUE_COUPON");
                    assertThat(a.params().path("rewardCode").asString()).as(id).isEqualTo(e[1]);
                }
                case "BADGE" -> {
                    assertThat(a.type()).as(id).isEqualTo("AWARD_BADGE");
                    assertThat(a.params().path("badgeCode").asString()).as(id).isEqualTo(e[1]);
                }
                case "MESSAGE" -> {
                    assertThat(a.type()).as(id).isEqualTo("SEND_MESSAGE");
                    assertThat(a.params().path("templateCode").asString()).as(id).isEqualTo(e[1]);
                    assertThat(explained(ev, "CMP-TB-OEF", "SEND_MESSAGE").amount()).as(id + " un messaggio non ha quantità").isNull();
                    if (params != null) {
                        assertThat(a.params().get("params")).as(id + " params").isEqualTo(json(params));
                    }
                }
                default -> throw new IllegalArgumentException(expected);
            }
        }
        if (expPts != null) {
            if ("NONE".equals(expPts)) {
                assertThat(ev.effects()).as(id + " nessun accredito").isEmpty();
            } else {
                assertThat(ev.effects()).as(id + " accredito").singleElement()
                        .extracting(GrantedEffect::amount).isEqualTo(Long.parseLong(expPts));
            }
        }
    }

    /** TB-CMP-EID: effectId = sha256(actionId + campaignCode + indiceEffetto) troncato a 26 caratteri (docs/03 §3.5 p. 4). */
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/cmp/effect-id.csv", numLinesToSkip = 1, delimiter = '|', quoteCharacter = '`')
    void effectId(String id, String description, String check, int index) throws Exception {
        String effects = "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":10},"
                + "{\"type\":\"GRANT_POINTS\",\"currency\":\"STS\",\"mode\":\"FIXED\",\"value\":5},"
                + "{\"type\":\"GRANT_PLAYS\",\"contestCode\":\"IW-AUTUNNO\",\"count\":1}]";
        Campaign c = campaign("CMP-TB-EID", 100, List.of(TYPE), null, null, effects, null, null, null, null);

        Evaluation ev = engine().evaluate(action("ACT-TB-EID-1", TYPE, TUESDAY, "{}"), silver(), List.of(c), StubCounters.zero());

        switch (check) {
            case "SPEC_PTS" -> assertThat(ev.effects().get(index).effectId()).as(id)
                    .isEqualTo(sha26("ACT-TB-EID-1" + "CMP-TB-EID" + index));
            case "SPEC_ACTION" -> assertThat(ev.actionEffects().get(0).effectId()).as(id)
                    .isEqualTo(sha26("ACT-TB-EID-1" + "CMP-TB-EID" + index));
            case "SAME" -> {
                Evaluation again = engine().evaluate(action("ACT-TB-EID-1", TYPE, TUESDAY, "{}"), silver(), List.of(c), StubCounters.zero());
                assertThat(ids(again)).as(id).isEqualTo(ids(ev));
            }
            case "DIFF" -> {
                Evaluation other = engine().evaluate(action("ACT-TB-EID-2", TYPE, TUESDAY, "{}"), silver(), List.of(c), StubCounters.zero());
                assertThat(ids(other)).as(id).doesNotContainAnyElementsOf(ids(ev));
            }
            default -> throw new IllegalArgumentException(check);
        }
        assertThat(ids(ev)).as(id + " 26 caratteri esadecimali").allSatisfy(x -> assertThat(x).matches("[0-9a-f]{26}"));
    }

    // ---------- supporto ----------

    private static GrantedEffect grant(Evaluation ev, String campaignCode, String currency) {
        return ev.effects().stream()
                .filter(g -> g.campaignCode().equals(campaignCode) && g.currency().equals(currency))
                .findFirst().orElse(null);
    }

    private static Evaluation.EffectResult explained(Evaluation ev, String code, String type) {
        return CmpEngineFixture.result(ev, code).effects().stream().filter(e -> e.type().equals(type)).findFirst().orElseThrow();
    }

    private static List<String> ids(Evaluation ev) {
        List<String> ids = new ArrayList<>();
        ev.effects().forEach(g -> ids.add(g.effectId()));
        ev.actionEffects().forEach(a -> ids.add(a.effectId()));
        return ids;
    }

    private static String sha26(String s) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash).substring(0, 26);
    }
}
