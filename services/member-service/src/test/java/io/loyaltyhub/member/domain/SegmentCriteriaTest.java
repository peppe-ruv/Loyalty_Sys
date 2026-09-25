package io.loyaltyhub.member.domain;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Criteri dei segmenti dinamici (docs/03 §10, formato §3.3): valutazione e validazione, senza Spring. */
class SegmentCriteriaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");

    private static SegmentFacts member(String tier, String status, List<String> labels, String city, long balance,
                                       Instant lastActivity, Map<String, SegmentFacts.ActionWindow> actions, double amount90d) {
        return new SegmentFacts("MBR-000002", "Marco Bianchi", status, tier, labels,
                MAPPER.readTree("{\"householdSize\": 3, \"preferredChannel\": \"APP\"}"),
                NOW.minus(Duration.ofDays(730)), LocalDate.of(1987, 6, 2), city, balance, 5000, lastActivity,
                actions, amount90d);
    }

    private static final SegmentFacts MARCO = member("SILVER", "ACTIVE", List.of(), "Milano", 1850,
            NOW.minus(Duration.ofDays(1)),
            Map.of("purchase.completed", new SegmentFacts.ActionWindow(2, 7)), 107.4);
    private static final SegmentFacts DAVIDE = member("GOLD", "ACTIVE", List.of("ebill", "directdebit"), "Torino", 12300,
            NOW.minus(Duration.ofDays(2)), Map.of(), 195.5);
    private static final SegmentFacts STEFANO = member("GOLD", "ACTIVE", List.of(), "Verona", 5400,
            NOW.minus(Duration.ofDays(60)), Map.of(), 55);
    private static final SegmentFacts ROBERTO = member("BASE", "BLOCKED", List.of(), "Genova", 320, null, Map.of(), 0);

    private static JsonNode json(String s) {
        return MAPPER.readTree(s);
    }

    private static boolean matches(String criteria, SegmentFacts m) {
        return SegmentCriteria.matches(json(criteria), m, NOW);
    }

    @Test
    void tierInGoldPlatinumMatchesTheGoldMembers() {
        String c = "{\"op\":\"all\",\"rules\":[{\"field\":\"member.tier\",\"cmp\":\"in\",\"value\":[\"GOLD\",\"PLATINUM\"]}]}";
        assertThat(matches(c, DAVIDE)).isTrue();
        assertThat(matches(c, STEFANO)).isTrue();
        assertThat(matches(c, MARCO)).isFalse();
        // anche una foglia sola, senza gruppo, e senza il prefisso member.
        assertThat(matches("{\"field\":\"tier\",\"cmp\":\"eq\",\"value\":\"SILVER\"}", MARCO)).isTrue();
    }

    @Test
    void labelsDigitalAndNotEbillFollowTheSeedDefinitions() {
        String digital = "{\"op\":\"all\",\"rules\":[{\"field\":\"member.labels\",\"cmp\":\"contains\",\"value\":\"ebill\"},"
                + "{\"field\":\"member.labels\",\"cmp\":\"contains\",\"value\":\"directdebit\"}]}";
        String notEbill = "{\"op\":\"all\",\"rules\":[{\"field\":\"member.status\",\"cmp\":\"eq\",\"value\":\"ACTIVE\"},"
                + "{\"field\":\"member.labels\",\"cmp\":\"ncontains\",\"value\":\"ebill\"}]}";
        assertThat(matches(digital, DAVIDE)).isTrue();
        assertThat(matches(digital, MARCO)).isFalse();
        assertThat(matches(notEbill, MARCO)).isTrue();
        assertThat(matches(notEbill, DAVIDE)).isFalse();
        assertThat(matches(notEbill, ROBERTO)).as("i non attivi restano fuori").isFalse();
        // dopo SCN-DIGITAL Marco ha le due etichette
        SegmentFacts digitalMarco = member("SILVER", "ACTIVE", List.of("ebill", "directdebit"), "Milano", 1850,
                NOW, Map.of(), 0);
        assertThat(matches(digital, digitalMarco)).isTrue();
        assertThat(matches(notEbill, digitalMarco)).isFalse();
    }

    @Test
    void lastActivityDaysAgoAbsentIsFalseExceptNexists() {
        String atRisk = "{\"op\":\"all\",\"rules\":[{\"field\":\"member.lastActivityDaysAgo\",\"cmp\":\"gt\",\"value\":45}]}";
        assertThat(matches(atRisk, STEFANO)).isTrue();
        assertThat(matches(atRisk, MARCO)).isFalse();
        assertThat(matches(atRisk, ROBERTO)).as("mai attivo: campo assente → falso").isFalse();
        assertThat(matches("{\"field\":\"member.lastActivityDaysAgo\",\"cmp\":\"nexists\"}", ROBERTO)).isTrue();
    }

    @Test
    void extendedFieldsBalanceActionsPurchasesCityAttributes() {
        assertThat(matches("{\"field\":\"member.balance.PTS\",\"cmp\":\"gte\",\"value\":10000}", DAVIDE)).isTrue();
        assertThat(matches("{\"field\":\"member.balance.PTS\",\"cmp\":\"gte\",\"value\":10000}", MARCO)).isFalse();
        assertThat(matches("{\"field\":\"member.lifetimeEarned.PTS\",\"cmp\":\"between\",\"value\":[1000,6000]}", MARCO)).isTrue();
        assertThat(matches("{\"field\":\"member.actions.purchase.completed.count30d\",\"cmp\":\"gte\",\"value\":2}", MARCO)).isTrue();
        assertThat(matches("{\"field\":\"member.actions.purchase.completed.total\",\"cmp\":\"eq\",\"value\":7}", MARCO)).isTrue();
        assertThat(matches("{\"field\":\"member.actions.purchase.completed.count30d\",\"cmp\":\"eq\",\"value\":0}", DAVIDE))
                .as("nessuna azione del tipo: conteggio zero").isTrue();
        assertThat(matches("{\"field\":\"member.purchases.amount90d\",\"cmp\":\"gt\",\"value\":150}", DAVIDE)).isTrue();
        assertThat(matches("{\"field\":\"member.city\",\"cmp\":\"eq\",\"value\":\"Torino\"}", DAVIDE)).isTrue();
        assertThat(matches("{\"field\":\"member.attributes.householdSize\",\"cmp\":\"gte\",\"value\":3}", MARCO)).isTrue();
        assertThat(matches("{\"field\":\"member.registeredDaysAgo\",\"cmp\":\"gte\",\"value\":365}", MARCO)).isTrue();
        assertThat(matches("{\"field\":\"member.age\",\"cmp\":\"between\",\"value\":[30,40]}", MARCO)).isTrue();
    }

    @Test
    void groupsAnyAndNotAndIncompatibleTypesNeverThrow() {
        String any = "{\"op\":\"any\",\"rules\":[{\"field\":\"member.city\",\"cmp\":\"eq\",\"value\":\"Torino\"},"
                + "{\"field\":\"member.tier\",\"cmp\":\"eq\",\"value\":\"SILVER\"}]}";
        assertThat(matches(any, MARCO)).isTrue();
        assertThat(matches(any, STEFANO)).isFalse();
        String not = "{\"op\":\"not\",\"rules\":[{\"field\":\"member.tier\",\"cmp\":\"eq\",\"value\":\"GOLD\"}]}";
        assertThat(matches(not, MARCO)).isTrue();
        assertThat(matches(not, DAVIDE)).isFalse();
        assertThat(matches("{\"field\":\"member.tier\",\"cmp\":\"gt\",\"value\":3}", MARCO)).isFalse();
        assertThat(matches("{\"field\":\"member.balance.PTS\",\"cmp\":\"eq\",\"value\":\"tanti\"}", MARCO)).isFalse();
        assertThat(matches("{\"field\":\"member.unknown\",\"cmp\":\"eq\",\"value\":1}", MARCO)).isFalse();
        assertThat(SegmentCriteria.matches(null, MARCO, NOW)).as("criteri vuoti: nessuno").isFalse();
        assertThat(SegmentCriteria.matches(json("{}"), MARCO, NOW)).isFalse();
    }

    @Test
    void validationReportsPathAndMessage() {
        assertThat(SegmentCriteria.validate(json("{}"))).extracting(SegmentCriteria.Issue::field).containsExactly("criteria");
        assertThat(SegmentCriteria.validate(json(
                "{\"op\":\"all\",\"rules\":[{\"field\":\"member.tier\",\"cmp\":\"in\",\"value\":[\"GOLD\"]}]}"))).isEmpty();
        List<SegmentCriteria.Issue> issues = SegmentCriteria.validate(json(
                "{\"op\":\"some\",\"rules\":[{\"field\":\"data.amount\",\"cmp\":\"gte\",\"value\":1},"
                        + "{\"field\":\"member.tier\",\"cmp\":\"like\",\"value\":\"G\"},"
                        + "{\"field\":\"member.balance.PTS\",\"cmp\":\"gt\",\"value\":\"x\"},"
                        + "{\"field\":\"member.tier\",\"cmp\":\"in\",\"value\":\"GOLD\"},"
                        + "{\"field\":\"member.city\",\"cmp\":\"exists\"}]}"));
        assertThat(issues).extracting(SegmentCriteria.Issue::field).containsExactly(
                "criteria.op", "criteria.rules[0].field", "criteria.rules[1].cmp", "criteria.rules[2].value",
                "criteria.rules[3].value");
        assertThat(SegmentCriteria.validate(json("{\"op\":\"all\",\"rules\":[]}")))
                .extracting(SegmentCriteria.Issue::field).containsExactly("criteria.rules");
        assertThat(SegmentCriteria.knownField("member.actions.app.login.daily.count30d")).isTrue();
        assertThat(SegmentCriteria.knownField("member.actions.count30d")).isFalse();
    }

    @Test
    void actionLabelsAreAddedOnceAndNeverRemoved() {
        assertThat(ActionLabels.after(List.of(), "ebill.activated")).containsExactly("ebill");
        assertThat(ActionLabels.after(List.of("ebill"), "directdebit.activated")).containsExactly("ebill", "directdebit");
        assertThat(ActionLabels.after(List.of("ebill"), "ebill.activated")).as("già presente").isNull();
        assertThat(ActionLabels.after(List.of("ebill"), "purchase.completed")).isNull();
    }

    /** Q-215 DECISA: valore non convertibile nel tipo della definizione dell'attributo → problema di tipo. */
    @Test
    void validationChecksDeclaredTypes() {
        Map<String, String> types = Map.of("householdSize", "NUMBER", "hasGasContract", "BOOLEAN", "since", "DATE");
        assertThat(SegmentCriteria.validate(json(
                "{\"field\":\"member.attributes.householdSize\",\"cmp\":\"gte\",\"value\":\"3\"}"), types)).isEmpty();
        List<SegmentCriteria.Issue> issues = SegmentCriteria.validate(json(
                "{\"op\":\"all\",\"rules\":[{\"field\":\"member.attributes.householdSize\",\"cmp\":\"gte\",\"value\":\"tre\"},"
                        + "{\"field\":\"member.attributes.hasGasContract\",\"cmp\":\"eq\",\"value\":\"TRUE\"},"
                        + "{\"field\":\"member.attributes.since\",\"cmp\":\"gt\",\"value\":\"2026-02-29\"},"
                        + "{\"field\":\"member.tier\",\"cmp\":\"eq\",\"value\":5}]}"), types);
        assertThat(issues).extracting(SegmentCriteria.Issue::field).containsExactly(
                "criteria.rules[0].value", "criteria.rules[1].value", "criteria.rules[2].value", "criteria.rules[3].value");
        assertThat(issues).allMatch(SegmentCriteria.Issue::typeMismatch);
        assertThat(SegmentCriteria.validate(json("{\"op\":\"any\",\"rules\":[]}"), types))
                .singleElement().extracting(SegmentCriteria.Issue::typeMismatch).isEqualTo(false);
    }

    /** Q-215 DECISA: stesso cast di campaign — testo numerico nel dato resta testo, numero ← testo numerico. */
    @Test
    void typedCastLikeCampaign() {
        SegmentFacts m = new SegmentFacts("MBR-X", "X", "ACTIVE", "GOLD", List.of("ebill"),
                json("{\"code\":\"5\",\"n\":5}"), NOW, null, "Torino", 1500, 5000, null, Map.of(), 0.0);
        assertThat(SegmentCriteria.matches(json("{\"field\":\"member.attributes.code\",\"cmp\":\"eq\",\"value\":5}"), m, NOW)).isFalse();
        assertThat(SegmentCriteria.matches(json("{\"field\":\"member.attributes.n\",\"cmp\":\"eq\",\"value\":\"5\"}"), m, NOW)).isTrue();
        assertThat(SegmentCriteria.matches(json("{\"field\":\"balance.PTS\",\"cmp\":\"gte\",\"value\":\"1500\"}"), m, NOW)).isTrue();
        assertThat(SegmentCriteria.matches(json("{\"op\":\"any\",\"rules\":[]}"), m, NOW)).as("Q-222").isFalse();
        assertThat(SegmentCriteria.matches(json("{\"field\":\"member.tier\",\"value\":\"GOLD\"}"), m, NOW)).as("Q-219").isFalse();
    }
}
