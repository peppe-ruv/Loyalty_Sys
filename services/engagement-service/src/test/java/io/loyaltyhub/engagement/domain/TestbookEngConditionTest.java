package io.loyaltyhub.engagement.domain;

import io.loyaltyhub.engagement.TestbookRows;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.aggregator.ArgumentsAccessor;
import org.junit.jupiter.params.provider.CsvFileSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Testbook TB-ENG (docs/testbook/TB-ENG-engagement.md): condizione opzionale delle regole di notifica
 * ({@link DataCondition}), stesso formato di docs/03 §3.3 sul solo spazio {@code data.*}. Righe CND: ogni comparatore
 * vero, falso e su campo assente; limiti; tipi incompatibili; array; gruppi.
 */
class TestbookEngConditionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Dati del fatto su cui si valutano tutte le righe di cnd.csv. */
    private static final JsonNode DATA = MAPPER.readTree("""
            {"amount": 150, "currency": "PTS", "role": "REFERRER", "tags": ["A", "B"], "code": "CAF-123", "flag": true,
             "n": "10", "nul": null, "items": [{"category": "FOOD", "qty": 2}, {"category": "TECH", "qty": 1}]}
            """);

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/engagement/cnd.csv", numLinesToSkip = 1, delimiter = '\t', quoteCharacter = '~',
            maxCharsPerColumn = 8192)
    void matches(ArgumentsAccessor row) throws Exception {
        String[] c = TestbookRows.columns(row);
        matches(c[0], c[1], c[2], Boolean.parseBoolean(c[3]));
    }

    private void matches(String id, String description, String condition, boolean expected) {
        // TESTBOOK: ambiguo, vedi le righe CND marcate AMBIGUO (null come assente, estremi di between, stringa numerica,
        // gruppi vuoti, comparatore sconosciuto).
        JsonNode c = "null".equals(condition) ? null : MAPPER.readTree(condition);
        assertThatCode(() -> DataCondition.matches(c, DATA)).as("mai eccezioni").doesNotThrowAnyException();
        assertThat(DataCondition.matches(c, DATA)).isEqualTo(expected);
    }
}
