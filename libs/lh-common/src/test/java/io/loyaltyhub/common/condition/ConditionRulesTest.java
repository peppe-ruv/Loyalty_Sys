package io.loyaltyhub.common.condition;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validazione di salvataggio comune (Q-215, Q-219, Q-222, Q-223, Q-224 decise): percorso del primo problema, o
 * {@code OK}. Campo {@code data.n} dichiarato numero, {@code data.d} data, {@code data.b} booleano, {@code data.s}
 * testo, {@code data.tags} elenco; gli altri senza tipo dichiarato.
 */
class ConditionRulesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ConditionRules.Schema SCHEMA = new ConditionRules.Schema() {
        @Override
        public TypedCast.Type declaredType(String field) {
            return switch (field) {
                case "data.n" -> TypedCast.Type.NUMBER;
                case "data.d" -> TypedCast.Type.DATE;
                case "data.b" -> TypedCast.Type.BOOLEAN;
                case "data.s" -> TypedCast.Type.STRING;
                default -> null;
            };
        }

        @Override
        public boolean isList(String field) {
            return "data.tags".equals(field);
        }
    };

    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            null                                                          | OK
            {}                                                            | OK
            {"op":"all","rules":[]}                                       | OK
            {"op":"any","rules":[]}                                       | c.rules
            {"op":"not","rules":[]}                                       | OK
            {"op":"xor","rules":[{"field":"data.x","cmp":"eq","value":1}]} | c.op
            {"rules":[{"field":"data.x","cmp":"eq","value":1}]}          | c.op
            {"op":"all"}                                                  | c.rules
            {"op":"all","rules":["x"]}                                    | c.rules[0]
            {"op":"all","rules":[{}]}                                     | c.rules[0].field
            {"cmp":"eq","value":1}                                        | c.field
            {"field":"","cmp":"eq","value":1}                             | c.field
            {"field":"data.x","value":1}                                  | c.cmp
            {"field":"data.x","cmp":"regex","value":"a"}                  | c.cmp
            {"field":"data.x","cmp":"eq"}                                 | c.value
            {"field":"data.x","cmp":"eq","value":null}                    | c.value
            {"field":"data.x","cmp":"exists"}                             | OK
            {"field":"data.x","cmp":"eq","value":[1]}                     | c.value
            {"field":"data.x","cmp":"in","value":"a"}                     | c.value
            {"field":"data.x","cmp":"in","value":["a",["b"]]}             | c.value[1]
            {"field":"data.x","cmp":"between","value":[1]}                | c.value
            {"field":"data.x","cmp":"between","value":[1,"2026-01-01"]}   | c.value
            {"field":"data.x","cmp":"between","value":[1,"2"]}            | OK
            {"field":"data.x","cmp":"gt","value":"abc"}                   | c.value
            {"field":"data.x","cmp":"gt","value":"5"}                     | OK
            {"field":"data.x","cmp":"gt","value":"2026-01-01"}            | OK
            {"field":"data.x","cmp":"gt","value":"2026-02-29"}            | c.value
            {"field":"data.x","cmp":"startsWith","value":5}               | c.value
            {"field":"data.n","cmp":"eq","value":"5"}                     | OK
            {"field":"data.n","cmp":"eq","value":" 5"}                    | c.value
            {"field":"data.n","cmp":"eq","value":"5e1"}                   | c.value
            {"field":"data.n","cmp":"in","value":[1,"+2"]}                | c.value[1]
            {"field":"data.n","cmp":"between","value":["1","2.5"]}        | OK
            {"field":"data.n","cmp":"startsWith","value":"1"}             | c.value
            {"field":"data.n","cmp":"contains","value":"1"}               | c.value
            {"field":"data.b","cmp":"eq","value":"true"}                  | OK
            {"field":"data.b","cmp":"eq","value":"TRUE"}                  | c.value
            {"field":"data.b","cmp":"gt","value":0}                       | c.value
            {"field":"data.d","cmp":"gte","value":"2026-02-28"}           | OK
            {"field":"data.d","cmp":"gte","value":"2026-02-29"}           | c.value
            {"field":"data.d","cmp":"gte","value":"2026-02-28T00:00:00Z"} | c.value
            {"field":"data.s","cmp":"eq","value":5}                       | c.value
            {"field":"data.s","cmp":"gt","value":"a"}                     | c.value
            {"field":"data.tags","cmp":"contains","value":"A"}            | OK
            """)
    void firstProblem(String node, String expected) {
        JsonNode n = JSON.readTree(node);
        List<ConditionRules.Issue> issues = ConditionRules.validate(n, "c", SCHEMA);
        if ("OK".equals(expected)) {
            assertThat(issues).as(node).isEmpty();
        } else {
            assertThat(issues).as(node).isNotEmpty();
            assertThat(issues.get(0).path()).as(node + " " + issues).isEqualTo(expected);
        }
    }

    @ParameterizedTest(name = "[{index}] {0}: tipo? {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            {"field":"data.n","cmp":"eq","value":"abc"}   | true
            {"field":"data.n","cmp":"regex","value":1}     | false
            {"op":"any","rules":[]}                        | false
            """)
    void typeMismatchFlag(String node, boolean typeMismatch) {
        List<ConditionRules.Issue> issues = ConditionRules.validate(JSON.readTree(node), "c", SCHEMA);
        assertThat(issues).singleElement().extracting(ConditionRules.Issue::typeMismatch).isEqualTo(typeMismatch);
    }
}
