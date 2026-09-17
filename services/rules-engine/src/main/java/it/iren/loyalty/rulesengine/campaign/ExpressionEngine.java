package it.iren.loyalty.rulesengine.campaign;

import java.util.Map;

/**
 * Porta del linguaggio di espressioni (RF-84): condizioni ed effetti delle campagne possono essere formule sul contesto
 * (member, transaction, event, wallet, referrer, executionContext). L'implementazione di default è Spring SpEL
 * ({@link SpelExpressionEngine}); nei test e nel simulatore può essere sostituita.
 */
public interface ExpressionEngine {
    boolean test(String expression, Map<String, Object> context);
    Object eval(String expression, Map<String, Object> context);

    default java.math.BigDecimal number(String expression, Map<String, Object> context) {
        Object v = eval(expression, context);
        if (v == null) return java.math.BigDecimal.ZERO;
        if (v instanceof java.math.BigDecimal b) return b;
        return new java.math.BigDecimal(v.toString());
    }
}
