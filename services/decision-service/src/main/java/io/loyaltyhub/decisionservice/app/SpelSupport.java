package io.loyaltyhub.decisionservice.app;

import io.loyaltyhub.decisionservice.domain.DecisionContext;
import io.loyaltyhub.decisionservice.domain.Ports;
import io.loyaltyhub.decisionservice.domain.ScoreExpression;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Formule di punteggio e condizioni delle offerte in SpEL (stesso linguaggio delle campagne, RF-84), valutate in un
 * contesto ristretto (niente riflessione, niente bean): l'utente del backoffice scrive ad esempio
 * {@code #value * (1 + #predictions['offerPropensity']) - #cost} oppure {@code #ctx.tier == 'TOP' && #ctx.recencyDays > 30}.
 */
public final class SpelSupport implements ScoreExpression, Ports.OfferCondition {
    private final ExpressionParser parser = new SpelExpressionParser();
    private final Map<String, Expression> cache = new ConcurrentHashMap<>();

    @Override
    public double evaluate(String expression, Map<String, Object> variables) {
        if (expression == null || expression.isBlank()) return 0;
        var ctx = SimpleEvaluationContext.forReadOnlyDataBinding().build();
        variables.forEach(ctx::setVariable);
        Object v = compile(expression).getValue(ctx);
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    @Override
    public boolean test(String expression, DecisionContext dc, Map<String, Object> event) {
        if (expression == null || expression.isBlank()) return true;
        var ctx = SimpleEvaluationContext.forReadOnlyDataBinding().build();
        ctx.setVariable("ctx", dc);
        ctx.setVariable("tier", dc.tier());
        ctx.setVariable("segments", dc.segments());
        ctx.setVariable("wallets", dc.walletActive());
        ctx.setVariable("predictions", dc.predictions());
        ctx.setVariable("riskLevel", dc.riskLevel());
        ctx.setVariable("recencyDays", dc.recencyDays());
        ctx.setVariable("frequency90d", dc.frequency90d());
        ctx.setVariable("monetary365d", dc.monetary365d());
        ctx.setVariable("event", event == null ? Map.of() : event);
        try { return Boolean.TRUE.equals(compile(expression).getValue(ctx, Boolean.class)); }
        catch (Exception e) { return false; }
    }

    private Expression compile(String expression) { return cache.computeIfAbsent(expression, parser::parseExpression); }
}
