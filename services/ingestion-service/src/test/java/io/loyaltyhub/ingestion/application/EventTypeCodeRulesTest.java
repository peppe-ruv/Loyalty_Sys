package io.loyaltyhub.ingestion.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regole del codice di un tipo azione custom (F-ING-06, BO-09, Q-89, Q-439): formato minuscolo a punti da 2 a 4 parti,
 * al massimo 60 caratteri, primo segmento diverso da {@code io} e {@code loyaltyhub}.
 */
class EventTypeCodeRulesTest {

    @ParameterizedTest
    @ValueSource(strings = {"io.loyaltyhub.effect.x", "io.loyaltyhub.action.x", "io.loyaltyhub.foo", "io.custom",
            "loyaltyhub.action.x", "loyaltyhub.points"})
    void reservedFirstSegmentsAreRejected(String code) {
        assertThat(EventTypeService.codeProblem(code)).isEqualTo(EventTypeService.CODE_RESERVED_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "meter", "Meter.reading", "meter..reading", "a.b.c.d.e", "1meter.reading",
            "meter.reading-sent"})
    void malformedCodesKeepTheFormatMessage(String code) {
        assertThat(EventTypeService.codeProblem(code)).isEqualTo(EventTypeService.CODE_FORMAT_MESSAGE);
    }

    @Test
    void tooLongCodesKeepTheFormatMessage() {
        assertThat(EventTypeService.codeProblem("a." + "b".repeat(59))).isEqualTo(EventTypeService.CODE_FORMAT_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"meter.reading.sent", "iot.sensor.read", "loyalty.card.linked", "ios.app.opened",
            "io1.device.paired"})
    void segmentsThatOnlyResembleTheReservedOnesAreValid(String code) {
        assertThat(EventTypeService.codeProblem(code)).isNull();
    }

    /** Nessun codice del seed (tutti tipi di sistema) cade nelle nuove regole. */
    @Test
    void everySeedCodeIsValid() throws Exception {
        JsonNode types;
        try (InputStream in = EventTypeCodeRulesTest.class.getResourceAsStream("/seed/event-types.json")) {
            assertThat(in).as("seed/event-types.json nel classpath").isNotNull();
            types = new ObjectMapper().readTree(in);
        }
        assertThat(types.size()).isPositive();
        for (JsonNode t : types) {
            String code = t.path("code").asString();
            assertThat(EventTypeService.codeProblem(code)).as(code).isNull();
        }
    }
}
