package it.iren.loyalty.rulesengine.domain;

import it.iren.loyalty.common.event.Currency;
import it.iren.loyalty.common.event.RewardingAction;
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
}
