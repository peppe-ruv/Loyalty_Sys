package io.loyaltyhub.ingestion.domain;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Set;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link SampleVariation}: piccole variazioni del sample_data nei limiti dello schema (ingestion §3, F-DEMO-03). */
class SampleVariationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void variesTopLevelNumbersOnlyWithinSpread() {
        JsonNode sample = mapper.readTree("""
                {"orderId":"ORD-1","amount":100.0,"currency":"EUR","reading":1000,
                 "items":[{"sku":"S","quantity":1,"unitPrice":10.0}]}""");
        RandomGenerator rnd = RandomGenerator.of("L64X128MixRandom");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            JsonNode v = SampleVariation.vary(sample, null, rnd);
            assertThat(v.path("orderId").asString()).isEqualTo("ORD-1");
            assertThat(v.path("currency").asString()).isEqualTo("EUR");
            assertThat(v.path("items")).isEqualTo(sample.path("items"));
            assertThat(v.path("amount").asDouble()).isBetween(80.0, 120.0);
            assertThat(v.path("reading").isIntegralNumber()).isTrue();
            assertThat(v.path("reading").asLong()).isBetween(900L, 1100L);
            seen.add(v.toString());
        }
        assertThat(seen).as("variazioni casuali").hasSizeGreaterThan(10);
        assertThat(sample.path("amount").asDouble()).as("il campione non cambia").isEqualTo(100.0);
    }

    @Test
    void staysWithinSchemaBounds() {
        JsonNode schema = mapper.readTree("""
                {"type":"object","properties":{
                  "rating":{"type":"integer","minimum":1,"maximum":5},
                  "score":{"type":"integer","minimum":0,"maximum":100},
                  "amount":{"type":"number","exclusiveMinimum":0}}}""");
        JsonNode sample = mapper.readTree("{\"rating\":5,\"score\":100,\"amount\":0.01}");
        RandomGenerator rnd = RandomGenerator.of("L64X128MixRandom");
        for (int i = 0; i < 200; i++) {
            JsonNode v = SampleVariation.vary(sample, schema, rnd);
            assertThat(v.path("rating").asLong()).isBetween(1L, 5L);
            assertThat(v.path("score").asLong()).isBetween(0L, 100L);
            assertThat(v.path("amount").asDouble()).isGreaterThan(0.0);
        }
    }

    @Test
    void nonObjectSampleIsReturnedAsIs() {
        JsonNode empty = mapper.createObjectNode();
        assertThat(SampleVariation.vary(empty, null, RandomGenerator.getDefault())).isEqualTo(empty);
        assertThat(SampleVariation.vary(null, null, RandomGenerator.getDefault())).isNull();
    }
}
