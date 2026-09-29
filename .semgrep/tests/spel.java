// Fixture di semgrep --test per .semgrep/rules/spel.yml. `ruleid:` = deve segnalare, `ok:` = non deve segnalare.
package io.loyaltyhub.fixture;

import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;

class SpelFixture {

    private static final String VALIDITY = "#points > 0";
    private final ExpressionParser parser = new SpelExpressionParser();
    private final SpelExpressionParser spel = new SpelExpressionParser();

    void literal() {
        // ok: lh-spel-espressione-non-costante
        parser.parseExpression("#points > 0");
        // ok: lh-spel-espressione-non-costante
        new SpelExpressionParser().parseExpression("'Ciao ' + #name");
        // ok: lh-spel-espressione-non-costante
        spel.parseExpression("""
                #points > 0
                """);
    }

    void constant() {
        // ok: lh-spel-espressione-non-costante
        parser.parseExpression(VALIDITY);
        // ok: lh-spel-espressione-non-costante
        spel.parseExpression(Constants.RULE);
    }

    void fromParameter(String expression) {
        // ruleid: lh-spel-espressione-non-costante
        parser.parseExpression(expression);
        // ruleid: lh-spel-espressione-non-costante
        spel.parseExpression(expression);
        // ruleid: lh-spel-espressione-non-costante
        new SpelExpressionParser().parseExpression(expression);
    }

    void concatenated(String name) {
        // ruleid: lh-spel-espressione-non-costante
        parser.parseExpression("#points > " + name);
    }

    void fromCall(Rule rule) {
        // ruleid: lh-spel-espressione-non-costante
        parser.parseExpression(rule.condition());
    }

    interface Rule { String condition(); }
    static final class Constants { static final String RULE = "#x"; }
}
