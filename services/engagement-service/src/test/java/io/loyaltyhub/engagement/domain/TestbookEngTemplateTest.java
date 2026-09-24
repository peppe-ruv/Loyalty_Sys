package io.loyaltyhub.engagement.domain;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class TestbookEngTemplateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest(name = "[{0}] template: {1}")
    @CsvFileSource(resources = "/testbook/engagement/TB-ENG-template.csv", numLinesToSkip = 1)
    void testbookTemplateRendering(String id, String template, String contextJson, String expected) throws Exception {
        JsonNode context = MAPPER.readTree(contextJson);
        String actual = TemplateEngine.renderText(template, context);
        assertThat(actual).isEqualTo(expected == null ? "" : expected);
    }

    @Test
    @DisplayName("[TB-ENG-TPL-009] Syntax validation error: unbalanced braces")
    void testbookSyntaxValidation() {
        List<String> problems = TemplateEngine.problems("Hello {{data.name");
        assertThat(problems).contains("graffe {{ }} non bilanciate");
    }
}
