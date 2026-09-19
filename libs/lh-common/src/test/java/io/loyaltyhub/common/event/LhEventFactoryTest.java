package io.loyaltyhub.common.event;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LhEventFactoryTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-18T10:15:00Z"), ZoneOffset.UTC);
    private final LhEventFactory ingestion = new LhEventFactory(clock, "ingestion");
    private final LhEventFactory campaign = new LhEventFactory(clock, "campaign");

    @Test
    void rootActionHasHopZeroAndSelfCorrelation() {
        LhEvent<Map<String, Object>> action = ingestion.newRoot(
                LhEventTypes.Action.PURCHASE_COMPLETED, "member:MBR-000003",
                Map.of("orderId", "ORD-1"), LhSource.source("ecommerce"), null);

        assertThat(action.specversion()).isEqualTo("1.0");
        assertThat(action.lhhop()).isZero();
        assertThat(action.lhcorrelationid()).isEqualTo(action.id());
        assertThat(action.lhcausationid()).isNull();
        assertThat(action.source()).isEqualTo("urn:loyaltyhub:source:ecommerce");
        assertThat(action.memberId()).isEqualTo("MBR-000003");
        assertThat(action.dataschema()).isEqualTo("urn:loyaltyhub:schema:action.purchase.completed:1");
    }

    @Test
    void childPropagatesCorrelationHopActorAndSetsCausation() {
        LhEvent<Map<String, Object>> action = ingestion.newRoot(
                LhEventTypes.Action.PURCHASE_COMPLETED, "member:MBR-000003",
                Map.of("orderId", "ORD-1"), LhSource.source("ecommerce"), "MARKETING:luca");

        LhEvent<Map<String, Object>> effect = campaign.childOf(
                action, LhEventTypes.Effect.POINTS_GRANT, Map.of("amount", 130));

        assertThat(effect.id()).isNotEqualTo(action.id());
        assertThat(effect.lhcorrelationid()).isEqualTo(action.id());
        assertThat(effect.lhcausationid()).isEqualTo(action.id());
        assertThat(effect.lhhop()).isEqualTo(action.hopOrZero());
        assertThat(effect.lhactor()).isEqualTo("MARKETING:luca");
        assertThat(effect.subject()).isEqualTo("member:MBR-000003");
        assertThat(effect.source()).isEqualTo("urn:loyaltyhub:service:campaign");
    }

    @Test
    void bridgeActionIncrementsHopAndUsesInternalSource() {
        LhEvent<Map<String, Object>> fact = campaign.newRoot(
                LhEventTypes.Fact.TIER_UPGRADED, "member:MBR-000010",
                Map.of("previousTier", "SILVER", "newTier", "GOLD"));

        LhEvent<Map<String, Object>> bridged = ingestion.bridgeAction(
                fact, LhEventTypes.Action.TIER_UPGRADED, Map.of("previousTier", "SILVER", "newTier", "GOLD"));

        assertThat(bridged.source()).isEqualTo(LhSource.INTERNAL);
        assertThat(bridged.lhhop()).isEqualTo(fact.hopOrZero() + 1);
        assertThat(bridged.time()).isEqualTo(fact.time());
        assertThat(bridged.subject()).isEqualTo(fact.subject());
        assertThat(bridged.lhcorrelationid()).isEqualTo(fact.lhcorrelationid());
        assertThat(bridged.lhcausationid()).isEqualTo(fact.id());
    }

    @Test
    void roundTripsThroughJson() throws Exception {
        var mapper = LhJson.mapper();
        LhEvent<Map<String, Object>> action = ingestion.newRoot(
                LhEventTypes.Action.PURCHASE_COMPLETED, "member:MBR-1", Map.of("amount", 130),
                LhSource.source("ecommerce"), null);
        String json = mapper.writeValueAsString(action);
        assertThat(json).doesNotContain("lhcausationid"); // null omesso (NON_NULL)
        assertThat(json).contains("\"specversion\":\"1.0\"");

        var back = mapper.readValue(json, LhEvent.class);
        assertThat(back.id()).isEqualTo(action.id());
        assertThat(back.type()).isEqualTo(action.type());
        assertThat(back.time()).isEqualTo(action.time());
    }
}
