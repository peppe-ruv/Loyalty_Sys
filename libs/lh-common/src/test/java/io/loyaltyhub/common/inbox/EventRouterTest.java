package io.loyaltyhub.common.inbox;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.metrics.LhMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Instradamento per type esatto e per famiglia ({@code io.loyaltyhub.action.*}), senza database. */
class EventRouterTest {

    private final IdempotentHandler direct = new IdempotentHandler(null) {
        @Override
        public boolean handle(String consumer, LhEvent<JsonNode> event, Consumer<LhEvent<JsonNode>> logic) {
            logic.accept(event);
            return true;
        }
    };

    @Test
    void exactTypeWinsOverFamilyAndUnknownFamiliesAreIgnored() {
        List<String> exact = new ArrayList<>();
        List<String> family = new ArrayList<>();
        EventRouter router = new EventRouter("test", List.of(
                handler(Set.of("io.loyaltyhub.action.purchase.completed"), exact),
                handler(Set.of("io.loyaltyhub.action.*"), family)), direct, new LhMetrics(new SimpleMeterRegistry()));

        assertThat(router.route(event("io.loyaltyhub.action.purchase.completed"))).isTrue();
        assertThat(router.route(event("io.loyaltyhub.action.custom.quiz"))).isTrue();
        assertThat(router.route(event("io.loyaltyhub.fact.contest.won"))).isFalse();

        assertThat(exact).containsExactly("io.loyaltyhub.action.purchase.completed");
        assertThat(family).containsExactly("io.loyaltyhub.action.custom.quiz");
        assertThat(router.handles("io.loyaltyhub.action.anything")).isTrue();
        assertThat(router.handles("io.loyaltyhub.effect.plays.grant")).isFalse();
    }

    @Test
    void twoHandlersForTheSameFamilyAreRejected() {
        assertThatThrownBy(() -> new EventRouter("test", List.of(
                handler(Set.of("io.loyaltyhub.action.*"), new ArrayList<>()),
                handler(Set.of("io.loyaltyhub.action.*"), new ArrayList<>())), direct, new LhMetrics(new SimpleMeterRegistry())))
                .isInstanceOf(IllegalStateException.class);
    }

    private static EventHandler handler(Set<String> types, List<String> seen) {
        return new EventHandler() {
            @Override
            public Set<String> handledTypes() {
                return types;
            }

            @Override
            public void handle(LhEvent<JsonNode> event) {
                seen.add(event.type());
            }
        };
    }

    private static LhEvent<JsonNode> event(String type) {
        return new LhEvent<>("1.0", "id-" + type, "urn:loyaltyhub:test", type, "member:MBR-000001", Instant.now(),
                "application/json", null, "aurora", "c", null, 0, null, null);
    }
}
