package io.loyaltyhub.ingestion.domain;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Limiti di forma dell'envelope (lunghezze, NUL) che altrimenti farebbero fallire la scrittura in PostgreSQL. */
class EnvelopeLimitsTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void attributesWithinLimitsPassAndProblemsNeverEchoTheValue() {
        assertThat(EnvelopeLimits.attributeProblem("id", "e-1")).isNull();
        assertThat(EnvelopeLimits.attributeProblem("id", null)).as("l'obbligatorietà è della pipeline").isNull();
        assertThat(EnvelopeLimits.attributeProblem("id", "x".repeat(EnvelopeLimits.MAX_ID))).isNull();
        assertThat(EnvelopeLimits.attributeProblem("id", "x".repeat(EnvelopeLimits.MAX_ID + 1)))
                .isEqualTo("id troppo lungo (al massimo 256 caratteri)");
        assertThat(EnvelopeLimits.attributeProblem("subject", "email:a\u0000b@example.org"))
                .isEqualTo("subject contiene il carattere NUL, non ammesso")
                .doesNotContain("example.org");
        assertThat(EnvelopeLimits.attributeProblem("time", "t".repeat(65))).contains("time troppo lungo");
    }

    @Test
    void textProblemNamesTheFieldAndStorableTextHasNoNul() {
        assertThat(EnvelopeLimits.textProblem("orderId", "o".repeat(246), 245))
                .isEqualTo("orderId troppo lungo (al massimo 245 caratteri)");
        assertThat(EnvelopeLimits.textProblem("orderId", "o".repeat(9999), null)).isNull();
        assertThat(EnvelopeLimits.withoutNul("id\u0000x")).isEqualTo("id\uFFFDx");
        assertThat(EnvelopeLimits.withoutNul("pulito")).isEqualTo("pulito");
        assertThat(EnvelopeLimits.withoutNul(null)).isNull();
    }

    @Test
    void nulIsFoundAtAnyDepthInKeysAndValues() {
        assertThat(EnvelopeLimits.containsNul(json.readTree("{\"a\":1,\"b\":[\"x\",{\"c\":\"ok\"}]}"))).isFalse();
        assertThat(EnvelopeLimits.containsNul(json.readTree("{\"b\":[\"x\",{\"c\":\"o\\u0000k\"}]}"))).isTrue();
        assertThat(EnvelopeLimits.containsNul(json.readTree("{\"k\\u0000\":1}"))).isTrue();
        assertThat(EnvelopeLimits.containsNul(null)).isFalse();
    }
}
