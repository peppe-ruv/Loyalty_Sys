package io.loyaltyhub.common.testbook;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.common.inbox.EventRouter;
import io.loyaltyhub.common.inbox.IdempotentHandler;
import io.loyaltyhub.common.inbox.ProcessedEvents;
import io.loyaltyhub.common.metrics.LhMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.loyaltyhub.common.testbook.TestbookPltSupport.outcome;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-PLT §IDM — instradamento per {@code type} ({@link EventRouter}, docs/06 §5) e consumo idempotente
 * ({@link IdempotentHandler}, docs/06 §1, RNF-03): un {@code type} non registrato è ignorato senza scrivere
 * {@code processed_event} (docs/05 §1); un doppio invio non riesegue la logica; handler per famiglia {@code .*};
 * contesto di log MDC dell'evento (docs/06 §8, RNF-10). La parte transazionale sul DB è in {@code TestbookPltRelayIT}.
 */
class TestbookPltRoutingTest {

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/plt/routing.csv", numLinesToSkip = 1)
    void routing(String id, String description, String kase, String expected) {
        assertThat(outcome(() -> run(kase))).as("%s: %s", id, description).isEqualTo(expected);
    }

    /** Registro {@code processed_event} in memoria, stessa semantica di {@code ON CONFLICT DO NOTHING}. */
    static final class MemoryProcessed extends ProcessedEvents {
        final Set<String> keys = new HashSet<>();

        MemoryProcessed() {
            super(null);
        }

        @Override
        public boolean markProcessed(String consumer, String eventId) {
            return keys.add(consumer + "|" + eventId);
        }

        @Override
        public boolean isProcessed(String consumer, String eventId) {
            return keys.contains(consumer + "|" + eventId);
        }
    }

    /** Handler che registra gli eventi ricevuti (e lo MDC al momento della chiamata). */
    static final class Recording implements EventHandler {
        final String name;
        final Set<String> types;
        final List<String> seen = new ArrayList<>();
        final List<Map<String, String>> mdc = new ArrayList<>();

        Recording(String name, String... types) {
            this.name = name;
            this.types = Set.of(types);
        }

        @Override
        public Set<String> handledTypes() {
            return types;
        }

        @Override
        public void handle(LhEvent<JsonNode> event) {
            seen.add(event.id());
            Map<String, String> ctx = MDC.getCopyOfContextMap();
            mdc.add(ctx == null ? Map.of() : ctx);
        }
    }

    private static LhEvent<JsonNode> event(String id, String type) {
        return new LhEvent<>("1.0", id, "urn:loyaltyhub:source:ecommerce", type, "member:MBR-000003",
                Instant.parse("2026-09-24T10:00:00Z"), "application/json", "urn:x:1", "aurora", "CORR-1", null, 0, null,
                JsonNodeFactory.instance.objectNode());
    }

    private Object run(String kase) {
        MemoryProcessed processed = new MemoryProcessed();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LhMetrics metrics = new LhMetrics(registry);
        IdempotentHandler idempotent = new IdempotentHandler(processed);
        Recording purchase = new Recording("purchase", LhEventTypes.Action.PURCHASE_COMPLETED);
        Recording anyAction = new Recording("anyAction", "io.loyaltyhub.action.*");
        EventRouter router = new EventRouter("lh-wallet", List.of(purchase, anyAction), idempotent, metrics);
        String purchaseType = LhEventTypes.Action.PURCHASE_COMPLETED;
        return switch (kase) {
            case "exact.first" -> router.route(event("E1", purchaseType)) + " " + purchase.seen + " " + anyAction.seen;
            case "exact.duplicate" -> {
                router.route(event("E1", purchaseType));
                yield router.route(event("E1", purchaseType)) + " " + purchase.seen;
            }
            case "exact.twoIds" -> {
                router.route(event("E1", purchaseType));
                router.route(event("E2", purchaseType));
                yield purchase.seen;
            }
            case "perConsumer" -> {
                EventRouter other = new EventRouter("lh-campaign", List.of(purchase), idempotent, metrics);
                router.route(event("E1", purchaseType));
                yield other.route(event("E1", purchaseType)) + " " + purchase.seen;
            }
            case "unknown.ignored" -> router.route(event("E9", LhEventTypes.Fact.WALLET_POINTS_EARNED)) + " "
                    + processed.keys.size();
            case "unknown.handles" -> router.handles(LhEventTypes.Fact.WALLET_POINTS_EARNED);
            case "family.catches" -> router.route(event("E3", LhEventTypes.Action.APP_LOGIN_DAILY)) + " " + anyAction.seen;
            case "family.exactWins" -> {
                router.route(event("E4", purchaseType));
                yield purchase.seen + " " + anyAction.seen;
            }
            case "family.otherFamily" -> router.handles(LhEventTypes.Effect.POINTS_GRANT);
            case "family.prefixOnly" -> router.handles("io.loyaltyhub.actionx.foo");
            case "type.null" -> router.route(event("E5", null)) + " " + processed.keys.size();
            case "config.duplicateType" -> new EventRouter("lh-x", List.of(purchase,
                    new Recording("dup", LhEventTypes.Action.PURCHASE_COMPLETED)), idempotent, metrics);
            case "config.duplicateFamily" -> new EventRouter("lh-x", List.of(anyAction,
                    new Recording("dup", "io.loyaltyhub.action.*")), idempotent, metrics);
            case "metrics.consumed" -> {
                router.route(event("E6", purchaseType));
                router.route(event("E6", purchaseType));
                router.route(event("E7", LhEventTypes.Fact.WALLET_POINTS_EARNED));
                yield registry.get("lh_events_consumed_total").tag("type", purchaseType).counter().count() + " "
                        + registry.find("lh_events_consumed_total").tag("type", LhEventTypes.Fact.WALLET_POINTS_EARNED)
                        .counters().size();
            }
            case "metrics.handlerTime" -> {
                router.route(event("E8", purchaseType));
                yield registry.get("lh_handler_seconds").tag("type", purchaseType).timer().count();
            }
            case "logic.throws" -> {
                EventHandler failing = new EventHandler() {
                    @Override
                    public Set<String> handledTypes() {
                        return Set.of(LhEventTypes.Effect.COUPON_ISSUE);
                    }

                    @Override
                    public void handle(LhEvent<JsonNode> event) {
                        throw new IllegalStateException("guasto");
                    }
                };
                yield new EventRouter("lh-reward", List.of(failing), idempotent, metrics)
                        .route(event("E10", LhEventTypes.Effect.COUPON_ISSUE));
            }
            case "mdc.during" -> {
                router.route(event("E11", purchaseType));
                Map<String, String> ctx = purchase.mdc.get(0);
                yield ctx.get("eventId") + " " + ctx.get("eventType") + " " + ctx.get("correlationId") + " "
                        + ctx.get("memberId");
            }
            case "mdc.after" -> {
                MDC.clear();
                router.route(event("E12", purchaseType));
                yield MDC.getCopyOfContextMap() == null || MDC.getCopyOfContextMap().isEmpty();
            }
            default -> throw new IllegalArgumentException(kase);
        };
    }
}
