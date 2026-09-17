package io.loyaltyhub.rulesengine.domain;

import java.util.Map;

/** Espone la valutazione delle condizioni dichiarative alle campagne (stessa semantica di {@link RuleEvaluator}). */
public final class RuleEvaluatorBridge {
    private RuleEvaluatorBridge() {}
    public static boolean test(Rule.Condition c, Map<String, Object> attrs) {
        return RuleEvaluator.matches(new Rule("_", "_", "_", java.util.List.of(c), 0, 0, Map.of(), 0, null, null, true), attrs);
    }
}
