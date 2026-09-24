package io.loyaltyhub.engagement.domain;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Motore dei template (docs/servizi/engagement-service.md §5; F-MSG-02): segnaposto, assenti, formattatori, sintassi. */
class TemplateEngineTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode ctx() {
        return mapper.readTree("""
                { "data": { "amount": 162, "big": 12300, "ratio": 1.25, "text": "1500", "expiresAt": "2026-10-31T22:59:59Z",
                            "day": "2026-12-25", "items": [ { "sku": "SKU-1" } ], "nested": { "code": "X" }, "flag": true },
                  "member": { "firstName": "Marco", "tierCode": "SILVER" },
                  "event": { "type": "wallet.points.earned", "time": "2026-09-18T10:15:00Z" } }
                """);
    }

    @Test
    void replacesPathsOverDataMemberAndEvent() {
        assertThat(TemplateEngine.renderText("Hai guadagnato {{data.amount}} punti, {{member.firstName}}", ctx()))
                .isEqualTo("Hai guadagnato 162 punti, Marco");
        assertThat(TemplateEngine.renderText("{{ event.type }} · {{data.nested.code}} · {{data.items.0.sku}} · {{data.flag}}", ctx()))
                .isEqualTo("wallet.points.earned · X · SKU-1 · true");
        assertThat(TemplateEngine.renderText("Nessun segnaposto", ctx())).isEqualTo("Nessun segnaposto");
        assertThat(TemplateEngine.renderText(null, ctx())).isEmpty();
    }

    @Test
    void missingPathBecomesEmptyAndIsReported() {
        TemplateEngine.Rendered r = TemplateEngine.render("Ciao {{member.lastName}}! {{data.nested}}{{data.nope.deep}} fine", ctx());
        assertThat(r.text()).isEqualTo("Ciao !  fine");
        assertThat(r.missing()).containsExactly("member.lastName", "data.nested", "data.nope.deep");
        assertThat(TemplateEngine.renderText("{{amount}}", ctx())).as("senza radice: assente").isEmpty();
    }

    @Test
    void numberFormatterUsesItalianGrouping() {
        assertThat(TemplateEngine.renderText("{{data.big|number}}", ctx())).isEqualTo("12.300");
        assertThat(TemplateEngine.renderText("{{data.amount | number}}", ctx())).isEqualTo("162");
        assertThat(TemplateEngine.renderText("{{data.ratio|number}}", ctx())).isEqualTo("1,25");
        assertThat(TemplateEngine.renderText("{{data.text|number}}", ctx())).as("stringa numerica").isEqualTo("1.500");
        assertThat(TemplateEngine.renderText("{{member.firstName|number}}", ctx())).as("non numerico: invariato").isEqualTo("Marco");
    }

    @Test
    void dateFormatterUsesRomeCivilDay() {
        assertThat(TemplateEngine.renderText("{{data.expiresAt|date}}", ctx())).as("22:59Z è già il 31 a Roma")
                .isEqualTo("31 ottobre 2026");
        assertThat(TemplateEngine.renderText("{{data.day|date}}", ctx())).isEqualTo("25 dicembre 2026");
        assertThat(TemplateEngine.renderText("{{member.firstName|date}}", ctx())).as("non data: invariato").isEqualTo("Marco");
        assertThat(TemplateEngine.renderText("{{data.amount|upper}}", ctx())).as("formattatore sconosciuto: valore grezzo").isEqualTo("162");
    }

    @Test
    void noLogicIsEvaluated() {
        assertThat(TemplateEngine.renderText("{{#if data.amount}}sì{{/if}}", ctx())).isEqualTo("sì");
    }

    @Test
    void syntaxProblemsForTheEditor() {
        assertThat(TemplateEngine.problems("Hai {{data.amount|number}} punti, {{member.firstName}} ({{event.time|date}})")).isEmpty();
        assertThat(TemplateEngine.problems("{{amount}}")).singleElement().asString().contains("data., member. o event.");
        assertThat(TemplateEngine.problems("{{data.amount|euro}}")).singleElement().asString().contains("formattatore sconosciuto");
        assertThat(TemplateEngine.problems("{{ }}")).containsExactly("segnaposto vuoto");
        assertThat(TemplateEngine.problems("Ciao {{member.firstName")).singleElement().asString().contains("non bilanciate");
    }
}
