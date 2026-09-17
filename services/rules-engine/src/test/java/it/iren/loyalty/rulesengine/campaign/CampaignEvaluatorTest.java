package it.iren.loyalty.rulesengine.campaign;

import it.iren.loyalty.common.event.RewardingAction;
import it.iren.loyalty.rulesengine.domain.Rule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CampaignEvaluatorTest {
    private final Instant now = Instant.parse("2027-03-10T10:00:00Z");
    private final CampaignEvaluator ev = new CampaignEvaluator(new SpelExpressionEngine((c, v) -> c.equals("green-skus") && v.equals("MANUT")));
    private RewardingAction act(String t, Map<String, Object> a) { return new RewardingAction(t, "k:1:" + t, null, now, null, a); }
    private final CampaignEvaluator.MemberContext top = new CampaignEvaluator.MemberContext("m", "TOP", Set.of("vip"), Map.of("PREMIO", new CampaignEvaluator.WalletView(0, 6000, 0, 0, 0, 0)), Map.of(), Set.of(), null, null);

    @Test void spelConditionsEffectsAndExpiryFormula() {
        var c = new Campaign("cb", "1", "cashback", Campaign.Kind.DIRECT, new Campaign.Trigger(Campaign.Trigger.Type.PURCHASE_TRANSACTION, "TRANSACTION", null, null), null, null, true, null,
                List.of(new Campaign.Rule("r1", List.of(Campaign.Condition.expr("#transaction['grossValue'] > 50 and #member['tier'] == 'TOP' and #member['segments'].contains('vip')")),
                        List.of(new Campaign.Effect(Campaign.Effect.Type.ADD_UNITS, "PREMIO", null, null,
                                "#fn.round_down(#fn.percent_value_distribution(#transaction['grossValue'], {5000, 7500, 10000}, {0.01, 0.012, 0.015, 0}, #wallets['PREMIO'].earned))",
                                null, "#fn.add_days_to_date(#transaction['occurredAt'], 30)", null, Map.of())))),
                new Campaign.Limits(1, Campaign.Limits.Period.MONTHLY, 150, 0, Campaign.Limits.Period.NONE), 0, Map.of());
        var r = ev.evaluate(act("TRANSACTION", Map.of("amountEur", 3000.0)), List.of(c), top, Map.of(), now);
        assertThat(r.outcomes()).singleElement().satisfies(o -> { assertThat(o.units()).isEqualTo(40); assertThat(o.expiresAt()).isEqualTo(now.plus(Duration.ofDays(30))); });
        assertThat(ev.evaluate(act("TRANSACTION", Map.of("amountEur", 3000.0)), List.of(c), CampaignEvaluator.MemberContext.simple("m", "BASE"), Map.of(), now).skipped()).extracting(CampaignEvaluator.Skipped::reason).containsExactly("NO_RULE_MATCHED");
        assertThat(ev.evaluate(act("TRANSACTION", Map.of("amountEur", 3000.0)), List.of(c), top, Map.of("cb", new CampaignEvaluator.Usage(1, 0, 0)), now).outcomes()).isEmpty();
        assertThat(ev.evaluate(act("TRANSACTION", Map.of("amountEur", 3000.0)), List.of(c), top, Map.of("cb", new CampaignEvaluator.Usage(0, 0, 130)), now).outcomes().get(0).units()).isEqualTo(20);
    }

    @Test void collectionsLinesAndMultipleEffectTypes() {
        var c = new Campaign("s", "1", "shop", Campaign.Kind.DIRECT, new Campaign.Trigger(Campaign.Trigger.Type.PURCHASE_TRANSACTION, "TRANSACTION", null, new Campaign.LineFilter(Set.of(), Set.of(), Set.of(), Set.of("delivery"), Set.of(), Set.of("servizi"), Set.of(), null)), null, null, true, null,
                List.of(new Campaign.Rule("r", List.of(Campaign.Condition.expr("#lines.?[#fn.in_collection('green-skus', sku)].size() > 0")), List.of(Campaign.Effect.addUnitsPerEur("PREMIO", BigDecimal.ONE), Campaign.Effect.giveReward("borraccia"), Campaign.Effect.grantBadge("green"), Campaign.Effect.assignTier("PLUS")))), null, 0, null);
        var attrs = Map.<String, Object>of("amountEur", 129.0, "lines", List.of(Map.of("sku", "MANUT", "category", "servizi", "amountEur", 119.0, "labels", List.of()), Map.of("sku", "D", "category", "servizi", "amountEur", 10.0, "labels", List.of("delivery"))));
        var r = ev.evaluate(act("TRANSACTION", attrs), List.of(c), top, Map.of(), now);
        assertThat(r.outcomes()).extracting(CampaignEvaluator.Outcome::type).containsExactly(Campaign.Effect.Type.ADD_UNITS, Campaign.Effect.Type.GIVE_REWARD, Campaign.Effect.Type.GRANT_BADGE, Campaign.Effect.Type.ASSIGN_TIER);
        assertThat(r.outcomes().get(0).units()).isEqualTo(119);
    }

    @Test void percentValueDistributionAndReferralChain() {
        assertThat(PercentValueDistribution.apply(new BigDecimal(10000), List.of(new BigDecimal(5000), new BigDecimal(7500), new BigDecimal(10000)), List.of(new BigDecimal("0.01"), new BigDecimal("0.012"), new BigDecimal("0.015"), BigDecimal.ZERO), BigDecimal.ZERO)).isEqualByComparingTo("117.5");
        var grants = ReferralChain.distribute(List.of("l1", "l2"), List.of(new CampaignEvaluator.Outcome("c", "1", "r", Campaign.Effect.Type.ADD_UNITS, "PREMIO", 100, null, Map.of("level", "1"), null, null)), 3);
        assertThat(grants).singleElement().extracting(ReferralChain.Grant::beneficiaryMemberId).isEqualTo("l1");
        assertThat(Campaign.fromRule(new Rule("r1", "1", "X", List.of(), 300, 300, Map.of(), 0, null, null, true)).rules().get(0).effects()).hasSize(2);
    }
}
