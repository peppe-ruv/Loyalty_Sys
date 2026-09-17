package io.loyaltyhub.rulesengine.domain;

import io.loyaltyhub.common.event.Currency;
import io.loyaltyhub.common.event.RewardingAction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEvaluatorTest {
    private final RuleEvaluator evaluator = new RuleEvaluator();

    private RewardingAction action(String type, Map<String, Object> attrs) {
        return new RewardingAction(type, "test:1:" + type, null, Instant.now(), null, attrs);
    }

    @Test
    void dualCurrencyPostingsWithTierMultiplier() {
        Rule r = new Rule("r1", "1", "DIRECT_DEBIT_ACTIVATED", List.of(), 300, 300, Map.of("TOP", new BigDecimal("1.5")), 0, null, null, true);
        var postings = evaluator.evaluate(action("DIRECT_DEBIT_ACTIVATED", Map.of()), List.of(r), new RuleEvaluator.Context("TOP", 0));
        assertThat(postings).extracting(RuleEvaluator.Posting::currency, RuleEvaluator.Posting::amount)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(Currency.PREMIO, 450L), org.assertj.core.groups.Tuple.tuple(Currency.STATUS, 300L));
    }

    @Test
    void conditionsAndCapApply() {
        Rule r = new Rule("r2", "1", "BILL_PAID_ON_TIME", List.of(new Rule.Condition("amountEur", Rule.Operator.GTE, "50")), 100, 0, Map.of(), 120, null, null, true);
        assertThat(evaluator.evaluate(action("BILL_PAID_ON_TIME", Map.of("amountEur", 20)), List.of(r), new RuleEvaluator.Context("BASE", 0))).isEmpty();
        var capped = evaluator.evaluate(action("BILL_PAID_ON_TIME", Map.of("amountEur", 84.3)), List.of(r), new RuleEvaluator.Context("BASE", 80));
        assertThat(capped).singleElement().extracting(RuleEvaluator.Posting::amount).isEqualTo(40L);
    }

    @Test
    void nonStackableRuleBlocksFollowingNonStackable() {
        Rule a = new Rule("a", "1", "X", List.of(), 10, 0, Map.of(), 0, null, null, false);
        Rule b = new Rule("b", "1", "X", List.of(), 20, 0, Map.of(), 0, null, null, false);
        Rule c = new Rule("c", "1", "X", List.of(), 5, 0, Map.of(), 0, null, null, true);
        var postings = evaluator.evaluate(action("X", Map.of()), List.of(a, b, c), new RuleEvaluator.Context("BASE", 0));
        assertThat(postings).extracting(RuleEvaluator.Posting::ruleId).containsExactly("a", "c");
    }

    @Test
    void proportionalPointsOnFilteredLinesWithCampaignMultiplierAndLock() {
        Rule r = new Rule("shop", "1", "TRANSACTION", List.of(), 0, 0, Map.of("TOP", new BigDecimal("1.5")), 0, null, null, true,
                new Rule.Earning(BigDecimal.ONE, "amountEur", new BigDecimal("2"), new Rule.LineFilter(java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), java.util.Set.of("delivery"), java.util.Set.of()), null),
                Rule.Target.ALL, new Rule.Limits(0, 14, false));
        var attrs = Map.<String, Object>of("amountEur", 129.0, "lines", List.of(
                Map.of("sku", "MANUT", "amountEur", 119.0, "labels", List.of("green")),
                Map.of("sku", "DELIVERY", "amountEur", 10.0, "labels", List.of("delivery"))));
        var postings = evaluator.evaluate(action("TRANSACTION", attrs), List.of(r), new RuleEvaluator.Context("TOP", 0));
        // 119 € × 1 punto × 2 (campagna) × 1.5 (tier TOP) = 357, in sospeso 14 giorni
        assertThat(postings).singleElement().satisfies(p -> { assertThat(p.amount()).isEqualTo(357L); assertThat(p.lockDays()).isEqualTo(14); });
    }

    @Test
    void targetingUsageLimitsGeoAndStopAfter() {
        Rule seg = new Rule("seg", "1", "X", List.of(), 10, 0, Map.of(), 0, null, null, true, Rule.Earning.NONE, new Rule.Target(java.util.Set.of(), java.util.Set.of("vip"), java.util.Set.of()), Rule.Limits.NONE);
        assertThat(evaluator.evaluate(action("X", Map.of()), List.of(seg), new RuleEvaluator.Context("BASE", 0))).isEmpty();
        assertThat(evaluator.evaluate(action("X", Map.of()), List.of(seg), new RuleEvaluator.Context("BASE", 0, java.util.Set.of("vip"), Map.of()))).hasSize(1);

        Rule limited = new Rule("lim", "1", "X", List.of(), 10, 0, Map.of(), 0, null, null, true, Rule.Earning.NONE, Rule.Target.ALL, new Rule.Limits(1, 0, true));
        Rule after = new Rule("after", "1", "X", List.of(), 5, 0, Map.of(), 0, null, null, true);
        assertThat(evaluator.evaluate(action("X", Map.of()), List.of(limited, after), new RuleEvaluator.Context("BASE", 0, java.util.Set.of(), Map.of("lim", 1))))
                .extracting(RuleEvaluator.Posting::ruleId).containsExactly("after");
        assertThat(evaluator.evaluate(action("X", Map.of()), List.of(limited, after), new RuleEvaluator.Context("BASE", 0)))
                .extracting(RuleEvaluator.Posting::ruleId).containsExactly("lim");

        Rule geo = new Rule("geo", "1", "CHECK_IN", List.of(new Rule.Condition("lat", Rule.Operator.GEO_WITHIN, "45.0703,7.6869,150")), 30, 0, Map.of(), 0, null, null, true);
        assertThat(evaluator.evaluate(action("CHECK_IN", Map.of("lat", 45.0704, "lon", 7.6870)), List.of(geo), new RuleEvaluator.Context("BASE", 0))).hasSize(1);
        assertThat(evaluator.evaluate(action("CHECK_IN", Map.of("lat", 45.08, "lon", 7.70)), List.of(geo), new RuleEvaluator.Context("BASE", 0))).isEmpty();

        Rule code = new Rule("code", "1", "CODE_REDEEMED", List.of(new Rule.Condition("code", Rule.Operator.MATCHES, "WELCOME.*"), new Rule.Condition("labels", Rule.Operator.CONTAINS, "green")), 1, 0, Map.of(), 0, null, null, true,
                new Rule.Earning(null, null, null, null, "reward-welcome"), Rule.Target.ALL, Rule.Limits.NONE);
        var p = evaluator.evaluate(action("CODE_REDEEMED", Map.of("code", "WELCOME2027", "labels", List.of("green"))), List.of(code), new RuleEvaluator.Context("BASE", 0));
        assertThat(p).singleElement().extracting(RuleEvaluator.Posting::autoRewardId).isEqualTo("reward-welcome");
    }
}
