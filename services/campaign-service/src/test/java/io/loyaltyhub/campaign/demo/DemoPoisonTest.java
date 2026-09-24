package io.loyaltyhub.campaign.demo;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.kafka.NonRetryableEventException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Flag demo {@code _poison} di SCN-POISON (docs/10 §8): onorato solo col profilo {@code demo}. */
class DemoPoisonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void poisonedActionFailsWithANonRetryableErrorInDemo() {
        DemoPoison poison = new DemoPoison(env("demo"));
        assertThatThrownBy(() -> poison.check(action(Map.of("platform", "ANDROID", "_poison", true))))
                .isInstanceOf(NonRetryableEventException.class)
                .satisfies(e -> assertThat(((NonRetryableEventException) e).code()).isEqualTo("DEMO_POISON"));
        assertThatCode(() -> poison.check(action(Map.of("platform", "ANDROID")))).doesNotThrowAnyException();
        assertThatCode(() -> poison.check(action(Map.of("_poison", false)))).doesNotThrowAnyException();
    }

    @Test
    void flagIsIgnoredOutsideTheDemoProfile() {
        DemoPoison poison = new DemoPoison(env("local"));
        assertThatCode(() -> poison.check(action(Map.of("_poison", true)))).doesNotThrowAnyException();
    }

    private static MockEnvironment env(String profile) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profile);
        return env;
    }

    private LhEvent<JsonNode> action(Map<String, Object> data) {
        return new LhEvent<>("1.0", "EVT-P", "urn:loyaltyhub:source:app", "io.loyaltyhub.action.app.login.daily",
                "member:MBR-000002", Instant.now(), "application/json", null, "aurora", "EVT-P", null, 0, null,
                mapper.valueToTree(data));
    }
}
