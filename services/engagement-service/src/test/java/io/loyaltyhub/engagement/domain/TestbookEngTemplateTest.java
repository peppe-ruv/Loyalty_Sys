package io.loyaltyhub.engagement.domain;

import io.loyaltyhub.engagement.TestbookRows;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.aggregator.ArgumentsAccessor;
import org.junit.jupiter.params.provider.CsvFileSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-ENG (docs/testbook/TB-ENG-engagement.md): motore dei template ({@link TemplateEngine}). Righe TPL
 * (sostituzione dei segnaposto sul contesto {data, member, event}, assenti, caratteri speciali, formattatori
 * {@code |number} e {@code |date} in it-IT / Europe/Rome) e TPV (validazione della sintassi usata dalla gestione).
 */
class TestbookEngTemplateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Contesto fisso: i valori citati dalle righe di tpl.csv. */
    private static final JsonNode CONTEXT = MAPPER.readTree("""
            {"data": {"amount": 162, "zero": 0, "n999": 999, "n1000": 1000, "n1500": 1500, "big": 1234567, "neg": -1500,
                      "dec": 1.25, "half": 1.5, "round": 1.255, "even": 0.125, "str": "1500", "text": "abc",
                      "date": "2026-10-31", "ts": "2026-10-31T23:30:00Z", "ts2": "2026-10-31T22:59:59Z",
                      "dst": "2026-03-29T00:30:00Z", "ny": "2026-12-31T23:00:00Z", "leap": "2028-02-29",
                      "off": "2026-10-31T23:30:00+01:00", "bad": "2026-02-30", "obj": {"a": 1}, "arr": [{"name": "X"}],
                      "nul": null, "dollar": "$1 \\\\ fine", "tpl": "{{data.amount}}", "html": "<b>ciao</b>"},
             "member": {"memberId": "MBR-900001", "firstName": "Giulia"},
             "event": {"id": "EVT-1", "type": "wallet.points.earned"}}
            """);

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/engagement/tpl.csv", numLinesToSkip = 1, delimiter = '\t', quoteCharacter = '~',
            maxCharsPerColumn = 8192)
    void render(ArgumentsAccessor row) throws Exception {
        String[] c = TestbookRows.columns(row);
        render(c[0], c[1], c[2], c[3], c[4]);
    }

    private void render(String id, String description, String template, String expected, String missing) {
        // TESTBOOK: ambiguo, vedi TB-ENG-TPL-007, -008, -010, -014, -017, -027, -028, -030, -038, -039, -040
        // (valori non semplici, indici, spazi, null, HTML, arrotondamenti e ripieghi dei formattatori).
        String tpl = switch (template) {
            case "<null>" -> null;
            case "<empty>" -> "";
            default -> template;
        };
        TemplateEngine.Rendered r = TemplateEngine.render(tpl, CONTEXT);
        assertThat(r.text()).isEqualTo("<empty>".equals(expected) ? "" : expected);
        assertThat(r.missing()).containsExactlyElementsOf("-".equals(missing) ? List.of() : List.of(missing.split(" ")));
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/engagement/tpv.csv", numLinesToSkip = 1, delimiter = '\t', quoteCharacter = '~',
            maxCharsPerColumn = 8192)
    void syntax(ArgumentsAccessor row) throws Exception {
        String[] c = TestbookRows.columns(row);
        syntax(c[0], c[1], c[2], c[3]);
    }

    private void syntax(String id, String description, String template, String expected) {
        // TESTBOOK: ambiguo, vedi TB-ENG-TPV-007 (template assente considerato valido).
        List<String> problems = TemplateEngine.problems("<null>".equals(template) ? null : template);
        if ("OK".equals(expected)) {
            assertThat(problems).isEmpty();
        } else {
            assertThat(problems).anySatisfy(p -> assertThat(p).contains(expected));
        }
    }
}
