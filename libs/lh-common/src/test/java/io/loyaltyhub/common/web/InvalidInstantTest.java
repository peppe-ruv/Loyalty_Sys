package io.loyaltyhub.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Istante non ISO-8601 in un parametro (Q-532 causa (4), F2-SEC-12): gli 8 job demo leggono {@code asOf} come testo e lo
 * convertono nel controller con {@code Instant.parse}; una {@code DateTimeParseException} non catturata era un 500.
 * Ora {@link GlobalExceptionHandler} la porta a 400 RFC 9457, senza il testo ricevuto.
 */
class InvalidInstantTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Probe())
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @ParameterizedTest(name = "asOf={0} → 400")
    @ValueSource(strings = {"domani", "2026-13-45T00:00:00Z", "2026-09-30", "2026-09-30T10:00:00", "0", "%", "  x "})
    @DisplayName("un asOf non ISO-8601: 400 bad-request, non 500")
    void unparseableInstantIs400(String asOf) throws Exception {
        mvc.perform(post("/v1/probe/job").param("asOf", asOf))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("urn:loyaltyhub:problem:bad-request"))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Istante o data non valido: atteso il formato ISO-8601"))
                .andExpect(jsonPath("$.instance").value("/v1/probe/job"));
    }

    @Test
    @DisplayName("il 400 non riporta il testo ricevuto")
    void responseDoesNotEchoTheValue() throws Exception {
        String body = mvc.perform(post("/v1/probe/job").param("asOf", "secret-not-a-date"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("secret-not-a-date").doesNotContain("could not be parsed");
    }

    @Test
    @DisplayName("un istante valido o assente: invariato")
    void validInstantIsUnchanged() throws Exception {
        mvc.perform(post("/v1/probe/job").param("asOf", "2026-09-30T10:00:00Z")).andExpect(status().isOk());
        mvc.perform(post("/v1/probe/job")).andExpect(status().isOk());
    }

    @RestController
    static class Probe {

        @PostMapping("/v1/probe/job")
        Map<String, Object> job(@RequestParam(required = false) String asOf) {
            Instant at = asOf == null ? Instant.parse("2026-09-30T00:00:00Z") : Instant.parse(asOf);
            return Map.of("asOf", at.toString());
        }
    }
}
