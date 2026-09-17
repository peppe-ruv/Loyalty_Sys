package it.iren.loyalty.rulesengine.campaign;

import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.SpelCompilerMode;
import org.springframework.expression.spel.SpelParserConfiguration;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Motore di espressioni su Spring SpEL (RF-84), equivalente delle "expressions" di Open Loyalty (Symfony ExpressionLanguage).
 * Contesto in sola lettura (SimpleEvaluationContext: niente costruttori, riflessione o bean), espressioni compilate e cachate.
 * Funzioni registrate: round_up, round_down, to_date, timestamp, add_days_to_date, percent_value_distribution,
 * in_collection, days_between.
 */
public class SpelExpressionEngine implements ExpressionEngine {
    private final ExpressionParser parser = new SpelExpressionParser(new SpelParserConfiguration(SpelCompilerMode.MIXED, null));
    private final Map<String, Expression> cache = new ConcurrentHashMap<>();
    private final CollectionSource collections;

    public SpelExpressionEngine(CollectionSource collections) { this.collections = collections; }

    @Override public boolean test(String expression, Map<String, Object> context) {
        Object v = eval(expression, context);
        return v instanceof Boolean b ? b : v != null && !"false".equalsIgnoreCase(v.toString()) && !"0".equals(v.toString());
    }

    @Override public Object eval(String expression, Map<String, Object> context) {
        Expression e = cache.computeIfAbsent(expression, parser::parseExpression);
        return e.getValue(context(context));
    }

    private EvaluationContext context(Map<String, Object> vars) {
        SimpleEvaluationContext ctx = SimpleEvaluationContext.forReadOnlyDataBinding().withInstanceMethods().build();
        vars.forEach(ctx::setVariable);
        ctx.setVariable("fn", new Functions(collections));
        return ctx;
    }

    /** Funzioni disponibili come {@code #fn.nome(...)}. */
    public static final class Functions {
        private final CollectionSource collections;
        Functions(CollectionSource collections) { this.collections = collections; }
        public long round_up(Object v) { return new BigDecimal(v.toString()).setScale(0, RoundingMode.CEILING).longValue(); }
        public long round_down(Object v) { return new BigDecimal(v.toString()).setScale(0, RoundingMode.FLOOR).longValue(); }
        public Instant to_date(Object v) { return v instanceof Instant i ? i : Instant.parse(v.toString()); }
        public long timestamp(Object v) { return to_date(v).getEpochSecond(); }
        public Instant add_days_to_date(Object date, long days) { return to_date(date).plus(days, ChronoUnit.DAYS); }
        public long days_between(Object from, Object to) { return ChronoUnit.DAYS.between(to_date(from), to_date(to)); }
        public boolean in_collection(String collection, Object value) { return collections != null && value != null && collections.contains(collection, value.toString()); }
        /** Distribuzione per fasce (RF-86): {@code percent_value_distribution(progresso, [soglie cumulative], [percentuali], accumulato prima)}. */
        public BigDecimal percent_value_distribution(Object progress, List<?> thresholds, List<?> percents, Object before) {
            return PercentValueDistribution.apply(new BigDecimal(progress.toString()),
                    thresholds.stream().map(t -> new BigDecimal(t.toString())).toList(),
                    percents.stream().map(p -> new BigDecimal(p.toString())).toList(), new BigDecimal(before.toString()));
        }
    }
}
