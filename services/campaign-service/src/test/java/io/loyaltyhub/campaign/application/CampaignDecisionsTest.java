package io.loyaltyhub.campaign.application;

import io.loyaltyhub.campaign.api.CreateCampaignRequest;
import io.loyaltyhub.campaign.domain.Campaign;
import io.loyaltyhub.campaign.domain.CampaignStatus;
import io.loyaltyhub.campaign.engine.CampaignEngine;
import io.loyaltyhub.campaign.engine.Counters;
import io.loyaltyhub.campaign.engine.EvalAction;
import io.loyaltyhub.campaign.engine.Evaluation;
import io.loyaltyhub.campaign.engine.GrantedEffect;
import io.loyaltyhub.campaign.engine.MemberSnapshot;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scelte conservative decise in docs/15 per il salvataggio e il motore delle campagne, non coperte dalle righe del
 * testbook: pubblico con chiavi non previste (Q-214), passo di {@code PER_AMOUNT} non positivo (Q-228), date del
 * calendario non ISO-8601 anche da sole (Q-243), pubblico del portale per un membro sconosciuto (Q-211), tetto punti
 * per membro con accredito ridotto al residuo (Q-237 con Q-165).
 */
class CampaignDecisionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GRANT = "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":100}]";

    /** Solo {@link CampaignAdminService#validate} è invocato: nessuna dipendenza. */
    private final CampaignAdminService service = new CampaignAdminService(null, null, null, null, null, null, null,
            null, null, null, null, null, null, null);

    private List<String> validate(String audience, String effects, String schedule) {
        return service.validate(new CreateCampaignRequest("CMP-DEC", "Decisioni", null, null, null,
                List.of("purchase.completed"), json(audience), null, json(effects), null, json(schedule), 100, null,
                false, List.of(), null));
    }

    @Test
    void audienceAcceptsOnlyAllTiersSegments() {
        assertThat(validate("{\"all\":false,\"tiers\":[\"GOLD\"],\"segments\":[\"SEG-A\"]}", GRANT, null)).isEmpty();
        assertThat(validate(null, GRANT, null)).isEmpty();
        assertThat(validate("{\"all\":false,\"labels\":[\"VIP\"]}", GRANT, null))
                .singleElement().asString().contains("audience.labels");
        assertThat(validate("{\"all\":true,\"attributes\":{\"city\":\"Roma\"},\"labels\":[]}", GRANT, null)).hasSize(2);
        assertThat(validate("[\"GOLD\"]", GRANT, null)).singleElement().asString().contains("audience");
    }

    @Test
    void perAmountStepMustBePositive() {
        String step = "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"PER_AMOUNT\",\"amountField\":\"data.amount\","
                + "\"value\":1,\"unitStep\":%s,\"rounding\":\"FLOOR\"}]";
        assertThat(validate(null, step.formatted("0"), null)).singleElement().asString().contains("unitStep");
        assertThat(validate(null, step.formatted("-1"), null)).singleElement().asString().contains("unitStep");
        assertThat(validate(null, step.formatted("0.1"), null)).isEmpty();
    }

    @Test
    void scheduleDatesMustBeIsoEvenAlone() {
        assertThat(validate(null, GRANT, "{\"startAt\":\"2026-13-01\"}")).singleElement().asString().contains("startAt");
        assertThat(validate(null, GRANT, "{\"endAt\":\"domani\"}")).singleElement().asString().contains("endAt");
        assertThat(validate(null, GRANT, "{\"startAt\":\"2026-01-01T00:00:00Z\",\"endAt\":null}")).isEmpty();
        assertThat(validate(null, GRANT, "{\"startAt\":\"2026-02-01T00:00:00Z\",\"endAt\":\"2026-01-01T00:00:00Z\"}"))
                .singleElement().asString().contains("endAt deve essere successivo");
    }

    @Test
    void portalAudienceForUnknownMember() {
        assertThat(CampaignEngine.audienceOk(json("{\"all\":true,\"tiers\":[],\"segments\":[]}"), null)).isTrue();
        assertThat(CampaignEngine.audienceOk(null, null)).isTrue();
        assertThat(CampaignEngine.audienceOk(json("{\"all\":false,\"tiers\":[],\"segments\":[]}"), null)).isFalse();
        assertThat(CampaignEngine.audienceOk(json("{\"all\":true,\"tiers\":[\"GOLD\"],\"segments\":[]}"), null)).isFalse();
    }

    @Test
    void perMemberPointsCapReducesTheLastGrant() {
        Campaign c = new Campaign("ID-CAP", "CMP-CAP", "Tetto", null, null, null, List.of("purchase.completed"),
                json("{\"all\":true}"), json("{\"op\":\"all\",\"rules\":[]}"), json(GRANT),
                json("{\"perMemberPoints\":250}"), json("{}"), 100, null, false, false, false, List.of(),
                CampaignStatus.LIVE, 0, null, null);
        MemberSnapshot m = new MemberSnapshot("MBR-900001", "ACTIVE", "SILVER", List.of(), List.of(),
                JSON.createObjectNode(), Instant.parse("2025-01-10T10:00:00Z"), LocalDate.parse("1990-05-20"));
        EvalAction a = new EvalAction("ACT-CAP-1", "purchase.completed", "MBR-900001", "urn:loyaltyhub:source:ecommerce",
                Instant.parse("2026-09-15T09:00:00Z"), JSON.createObjectNode());

        Evaluation ev = new CampaignEngine(5).evaluate(a, m, List.of(c), memberPoints(200));

        assertThat(ev.effects()).singleElement().extracting(GrantedEffect::amount).isEqualTo(50L);
        assertThat(new CampaignEngine(5).evaluate(a, m, List.of(c), memberPoints(250)).effects()).isEmpty();
    }

    private static Counters memberPoints(long points) {
        return new Counters() {
            @Override
            public int memberMatches(String campaignId, String memberId, String period, String periodKey) {
                return 0;
            }

            @Override
            public long globalPointsDecided(String campaignId) {
                return 0;
            }

            @Override
            public long globalMatches(String campaignId) {
                return 0;
            }

            @Override
            public long historyActionCount(String memberId, String actionType) {
                return 0;
            }

            @Override
            public long historyDaysSinceLastAction(String memberId, String actionType) {
                return -1;
            }

            @Override
            public long memberPoints(String campaignId, String memberId) {
                return points;
            }
        };
    }

    private static JsonNode json(String text) {
        return text == null ? null : JSON.readTree(text);
    }
}
