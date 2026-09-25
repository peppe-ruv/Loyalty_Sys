package io.loyaltyhub.gamification;

import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class TestbookGamContestIT extends ContestIT {

    @Test
    @DisplayName("[TB-GAM-LFC-001] Publish senza istanti generati")
    void testPublishNoInstants() {
        String id = createTb("IW-TB-LFC-001", null).path("id").asString();
        sendTb("POST", "/v1/contests/" + id + "/transitions", "MARKETING:luca", Map.of("action", "SUBMIT"), 200);
        sendTb("POST", "/v1/contests/" + id + "/transitions", "LEGAL:elena", Map.of("action", "APPROVE", "comment", "ok"), 200);
        JsonNode result = sendTb("POST", "/v1/contests/" + id + "/transitions", "MARKETING:luca", Map.of("action", "PUBLISH"), 422);
        assertThat(result.path("code").asText()).isEqualTo("INSTANTS_NOT_GENERATED");
    }

    @Test
    @DisplayName("[TB-GAM-LFC-002] Transition APPROVED -> LIVE con istanti")
    void testPublishSuccess() {
        String id = createLiveTb("IW-TB-LFC-002", 5, true, null);
        assertThat(id).isNotNull();
    }

    @Test
    @DisplayName("[TB-GAM-LFC-003] Genera istanti su LIVE")
    void testGenerateOnLive() {
        String id = createLiveTb("IW-TB-LFC-003", 5, true, null);
        JsonNode result = sendTb("POST", "/v1/contests/" + id + "/instants/generate", "MARKETING:luca", null, 409);
        assertThat(result.path("code").asText()).isEqualTo("INSTANTS_LOCKED");
    }

    @Test
    @DisplayName("[TB-GAM-LFC-004] Admin legge /instants")
    void testReadInstantsAdmin() {
        JsonNode instants = sendTb("GET", "/v1/contests/IW-AUTUNNO/instants", "ADMIN:marta", null, 200);
        assertThat(instants.path("items").isArray()).isTrue();
    }

    @Test
    @DisplayName("[TB-GAM-LFC-005] Marketing legge /instants")
    void testReadInstantsMarketing() {
        sendTb("GET", "/v1/contests/IW-AUTUNNO/instants", "MARKETING:luca", null, 403);
    }

    @Test
    @DisplayName("[TB-GAM-INST-005] Fine concorso")
    void testEndContestVoidsInstants() {
        String id = createLiveTb("IW-TB-INST-005", 5, true, null);
        sendTb("POST", "/v1/contests/" + id + "/transitions", "MARKETING:luca", Map.of("action", "END"), 200);
        JsonNode ended = contestTb("IW-TB-INST-005");
        assertThat(ended.path("status").asText()).isEqualTo("ENDED");
        assertThat(ended.path("instants").path("open").asLong()).isZero();
    }

    // helper

    private JsonNode createTb(String code, Long seed) {
        Instant start = Instant.now().minus(Duration.ofDays(1));
        Map<String, Object> body = new HashMap<>(Map.of("code", code, "name", "Ruota di prova", "mechanic", "WHEEL",
                "startAt", start.toString(), "endAt", start.plus(Duration.ofDays(10)).toString(), "freePlayDaily", true,
                "distribution", "UNIFORM", "prizes", List.of(prizeTb("PTS-10", "POINTS", 10, null, 10))));
        if (seed != null) {
            body.put("seed", seed);
        }
        return sendTb("POST", "/v1/contests", "MARKETING:luca", body, 201);
    }

    private String createLiveTb(String code, int prizes, boolean freeDaily, Integer maxPerDay) {
        String id = createTb(code, 42L).path("id").asString();
        sendTb("POST", "/v1/contests/" + id + "/instants/generate", "MARKETING:luca", null, 200);
        sendTb("POST", "/v1/contests/" + id + "/transitions", "MARKETING:luca", Map.of("action", "SUBMIT"), 200);
        sendTb("POST", "/v1/contests/" + id + "/transitions", "LEGAL:elena", Map.of("action", "APPROVE", "comment", "ok"), 200);
        sendTb("POST", "/v1/contests/" + id + "/transitions", "MARKETING:luca", Map.of("action", "PUBLISH"), 200);
        return id;
    }

    private static Map<String, Object> prizeTb(String code, String type, Integer points, String rewardCode, int qty) {
        Map<String, Object> p = new HashMap<>(Map.of("code", code, "name", code, "type", type, "quantity", qty));
        if (points != null) p.put("points", points);
        if (rewardCode != null) p.put("rewardCode", rewardCode);
        return p;
    }

    private JsonNode contestTb(String code) {
        JsonNode items = sendTb("GET", "/v1/contests", "ADMIN:marta", null, 200);
        for (JsonNode c : items) {
            if (code.equals(c.path("code").asString())) {
                return c;
            }
        }
        throw new AssertionError("concorso assente: " + code);
    }

    private JsonNode sendTb(String method, String path, String actor, Object body, int expected) {
        int actualPort = (int) org.springframework.test.util.ReflectionTestUtils.getField(this, "port");
        var spec = org.springframework.web.client.RestClient.create("http://localhost:" + actualPort).method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(path).header("X-LH-Actor", actor);
        if (body != null) spec = spec.contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body);
        return spec.exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(res.getStatusCode().value()).as(method + " " + path + " → " + text).isEqualTo(expected);
            return text.isBlank() ? new tools.jackson.databind.ObjectMapper().createObjectNode() : new tools.jackson.databind.ObjectMapper().readTree(text);
        });
    }

}
