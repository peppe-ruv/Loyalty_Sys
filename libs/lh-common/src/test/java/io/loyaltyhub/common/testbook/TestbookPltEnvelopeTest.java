package io.loyaltyhub.common.testbook;

import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.event.JsonSchemaValidator;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.event.LhFamily;
import io.loyaltyhub.common.event.LhJson;
import io.loyaltyhub.common.event.LhSource;
import io.loyaltyhub.common.kafka.LoyaltyHubProperties;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.common.web.ActorContext;
import io.loyaltyhub.common.web.ActorHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.loyaltyhub.common.testbook.TestbookPltSupport.at;
import static io.loyaltyhub.common.testbook.TestbookPltSupport.outcome;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-PLT §ENV e §TOP — envelope CloudEvents 1.0 prodotto da {@link LhEventFactory} (docs/05 §2, §7) e instradamento su
 * topic e chiave (docs/05 §1, §6; ADR-004). Oracolo: attributi, topic e chiavi come scritti nella specifica; l'envelope
 * prodotto valida contro {@code contracts/events/envelope.schema.json}.
 */
class TestbookPltEnvelopeTest {

    private static final String NOW = "2026-09-24T10:00:00Z";
    private static final Instant PARENT_TIME = Instant.parse("2026-09-18T10:15:00Z");
    private static final String PARENT_ID = "01J8ZK3V7Q2M9T4B6N8R0XWYCD";
    private static final String CORRELATION = "01J8ZK3A0000000000000CORR0";
    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";

    private final ObjectMapper mapper = LhJson.create();
    private final LhEventFactory campaign = new LhEventFactory(at(NOW), "campaign");

    @AfterEach
    void clear() {
        ActorHolder.clear();
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/plt/envelope.csv", numLinesToSkip = 1)
    void envelope(String id, String description, String kase, String expected) {
        assertThat(outcome(() -> run(kase))).as("%s: %s", id, description).isEqualTo(expected);
    }

    private Object run(String kase) throws Exception {
        LhEvent<Map<String, Object>> root = campaign.newRoot(LhEventTypes.Action.PURCHASE_COMPLETED, "member:MBR-000003",
                Map.of("orderId", "ORD-1", "amount", 130, "currency", "EUR"), LhSource.source("ecommerce"), null);
        LhEvent<Map<String, Object>> parent = new LhEvent<>("1.0", PARENT_ID, LhSource.source("ecommerce"),
                LhEventTypes.Action.PURCHASE_COMPLETED, "member:MBR-000003", PARENT_TIME, "application/json",
                LhSource.schemaForType(LhEventTypes.Action.PURCHASE_COMPLETED, 1), "aurora", CORRELATION, null, 2,
                "CARE:paolo.care", Map.of("orderId", "ORD-1"));
        LhEvent<Map<String, Object>> child = campaign.childOf(parent, LhEventTypes.Effect.POINTS_GRANT, Map.of("amount", 1));
        return switch (kase) {
            // ---- radice ----
            case "root.specversion" -> root.specversion();
            case "root.idUlid" -> root.id().length() + ":" + root.id().matches(ULID);
            case "root.sourceService" -> new LhEventFactory(at(NOW), "campaign")
                    .newRoot(LhEventTypes.Fact.CAMPAIGN_EVALUATED, "member:MBR-000003", Map.of()).source();
            case "root.sourceExplicit" -> root.source();
            case "root.time" -> root.time();
            case "root.datacontenttype" -> root.datacontenttype();
            case "root.dataschemaAction" -> root.dataschema();
            case "root.dataschemaFact" -> campaign.newRoot(LhEventTypes.Fact.WALLET_POINTS_EARNED, "member:MBR-000003",
                    Map.of()).dataschema();
            case "root.dataschemaAudit" -> campaign.newRoot(LhEventTypes.Audit.ENTRY, "WALLET:MBR-000004", Map.of())
                    .dataschema();
            case "root.tenant" -> root.lhtenant();
            case "root.correlation" -> root.lhcorrelationid().equals(root.id());
            case "root.causation" -> root.lhcausationid();
            case "root.hop" -> root.lhhop();
            case "root.actorAbsent" -> root.lhactor();
            case "root.actorPresent" -> campaign.newRoot(LhEventTypes.Action.PURCHASE_COMPLETED, "member:MBR-000003",
                    Map.of(), LhSource.source("backoffice"), "MARKETING:luca.marketing").lhactor();
            // ---- figlio ----
            case "child.correlation" -> child.lhcorrelationid();
            case "child.causation" -> child.lhcausationid();
            case "child.hop" -> child.lhhop();
            case "child.subject" -> child.subject();
            case "child.time" -> child.time();
            case "child.sameBusinessTime" -> campaign.childSameBusinessTime(parent, LhEventTypes.Effect.POINTS_GRANT,
                    Map.of()).time();
            case "child.actor" -> child.lhactor();
            case "child.source" -> child.source();
            case "child.dataschema" -> child.dataschema();
            case "child.newId" -> !child.id().equals(PARENT_ID) && child.id().matches(ULID);
            case "child.parentWithoutCorrelation" -> campaign.childOf(new LhEvent<>("1.0", PARENT_ID, "urn:x", "t",
                    "member:MBR-000003", PARENT_TIME, null, null, null, null, null, null, null, Map.of()),
                    LhEventTypes.Effect.POINTS_GRANT, Map.of()).lhcorrelationid();
            case "child.parentWithoutHop" -> campaign.childOf(new LhEvent<>("1.0", PARENT_ID, "urn:x", "t",
                    "member:MBR-000003", PARENT_TIME, null, null, null, CORRELATION, null, null, null, Map.of()),
                    LhEventTypes.Effect.POINTS_GRANT, Map.of()).lhhop();
            case "childForSubject.subject" -> campaign.childForSubject(parent, LhEventTypes.Fact.REFERRAL_COMPLETED,
                    "member:MBR-000002", Map.of()).subject();
            case "childForSubject.correlation" -> campaign.childForSubject(parent, LhEventTypes.Fact.REFERRAL_COMPLETED,
                    "member:MBR-000002", Map.of()).lhcorrelationid();
            case "childForSubject.causation" -> campaign.childForSubject(parent, LhEventTypes.Fact.REFERRAL_COMPLETED,
                    "member:MBR-000002", Map.of()).lhcausationid();
            // ---- ponte fatti → azioni (docs/05 §7) ----
            case "bridge.source" -> bridge(0).source();
            case "bridge.hop0" -> bridge(0).lhhop();
            case "bridge.hop2" -> bridge(2).lhhop();
            case "bridge.hopNull" -> bridge(null).lhhop();
            case "bridge.time" -> bridge(0).time();
            case "bridge.subject" -> bridge(0).subject();
            case "bridge.causation" -> bridge(0).lhcausationid();
            case "bridge.correlation" -> bridge(0).lhcorrelationid();
            case "bridge.newId" -> !bridge(0).id().equals(PARENT_ID) && bridge(0).id().matches(ULID);
            case "bridge.dataschema" -> bridge(0).dataschema();
            case "bridge.actor" -> bridge(0).lhactor();
            // ---- identificativi e JSON ----
            case "ulid.monotonic" -> {
                List<String> ids = new ArrayList<>();
                for (int i = 0; i < 50; i++) {
                    ids.add(campaign.newRoot(LhEventTypes.Action.PURCHASE_COMPLETED, "member:X", Map.of()).id());
                }
                List<String> sorted = new ArrayList<>(ids);
                sorted.sort(String::compareTo);
                yield ids.equals(sorted) && ids.stream().distinct().count() == 50;
            }
            case "json.nullsOmitted" -> !mapper.writeValueAsString(root).contains("lhcausationid")
                    && !mapper.writeValueAsString(root).contains("lhactor");
            case "json.timeRfc3339" -> mapper.readTree(mapper.writeValueAsString(root)).path("time").asString();
            case "json.unknownTolerated" -> mapper.readValue("""
                    {"specversion":"1.0","id":"X","source":"urn:loyaltyhub:source:app","type":"io.loyaltyhub.action.app.login.daily",
                     "subject":"member:MBR-000001","time":"2026-09-19T07:05:00Z","foo":{"bar":1},"lhfuture":"x","data":{}}
                    """, LhEvent.class).type();
            case "json.hopAsNumber" -> mapper.readTree(mapper.writeValueAsString(root)).path("lhhop").isNumber();
            // ---- conformità all'envelope dei contratti ----
            case "schema.root" -> envelopeErrors(root);
            case "schema.child" -> envelopeErrors(child);
            case "schema.bridge" -> envelopeErrors(bridge(1));
            case "schema.audit" -> envelopeErrors(captureAudit("MARKETING:luca.marketing", false).event());
            // ---- topic e chiavi (docs/05 §1, ADR-004) ----
            case "topic.ACTION", "topic.EFFECT", "topic.FACT", "topic.AUDIT" ->
                    new LoyaltyHubProperties().topicFor(LhFamily.valueOf(kase.substring(6)));
            case "topic.all" -> String.join(" ", new LoyaltyHubProperties().getTopics().all());
            case "topic.dlq" -> new LoyaltyHubProperties().getTopics().getDlq();
            case "topic.custom" -> {
                LoyaltyHubProperties p = new LoyaltyHubProperties();
                p.getTopics().setActions("x.actions.v9");
                yield p.topicFor(LhFamily.ACTION);
            }
            case "family.action" -> LhFamily.of(LhEventTypes.Action.PURCHASE_COMPLETED);
            case "family.audit" -> LhFamily.of(LhEventTypes.Audit.ENTRY);
            case "family.short" -> LhFamily.of("purchase.completed");
            case "family.unknown" -> LhFamily.of("io.loyaltyhub.dlq.entry");
            case "family.null" -> LhFamily.of(null);
            case "writer.unknownFamily" -> new OutboxWriter(null, mapper, new LoyaltyHubProperties())
                    .write(campaign.newRoot("io.loyaltyhub.dlq.entry", "member:MBR-1", Map.of()));
            case "writer.familyTopic" -> {
                Captured c = new Captured();
                c.writer().write(child);
                yield c.topic + " " + c.key;
            }
            case "key.member" -> root.partitionKey();
            case "key.config" -> campaign.newRoot(LhEventTypes.Fact.EDITION_CLOSED, "edition:E2026", Map.of()).partitionKey();
            case "key.memberId.config" -> campaign.newRoot(LhEventTypes.Fact.EDITION_CLOSED, "edition:E2026", Map.of()).memberId();
            // ---- audit (docs/05 §6) ----
            case "audit.topicKey" -> {
                Captured c = captureAudit("MARKETING:luca.marketing", false);
                yield c.topic + " " + c.key;
            }
            case "audit.type" -> captureAudit("MARKETING:luca.marketing", false).event().type();
            case "audit.actorRequest" -> captureAudit("MARKETING:luca.marketing", false).event().lhactor();
            case "audit.actorAnonymous" -> captureAudit(null, false).event().lhactor();
            case "audit.actorJob" -> captureAudit("ADMIN:marta.admin", true).event().lhactor();
            case "audit.service" -> ((AuditEntry) captureAudit("ADMIN:marta.admin", false).event().data()).service()
                    + " " + captureAudit("ADMIN:marta.admin", false).event().source();
            case "audit.actions" -> Arrays.stream(AuditEntry.Action.values()).map(Enum::name).collect(Collectors.joining(" "));
            default -> throw new IllegalArgumentException("caso sconosciuto: " + kase);
        };
    }

    private LhEvent<Object> bridge(Integer factHop) {
        LhEvent<Map<String, Object>> fact = new LhEvent<>("1.0", PARENT_ID, LhSource.service("wallet"),
                LhEventTypes.Fact.TIER_UPGRADED, "member:MBR-000003", PARENT_TIME, "application/json",
                LhSource.schemaForType(LhEventTypes.Fact.TIER_UPGRADED, 1), "aurora", CORRELATION, UUID.randomUUID().toString(),
                factHop, "ADMIN:marta.admin", Map.of("previousTier", "SILVER", "newTier", "GOLD"));
        return new LhEventFactory(at(NOW), "ingestion").bridgeAction(fact, LhEventTypes.Action.TIER_UPGRADED, (Object) fact.data());
    }

    private String envelopeErrors(LhEvent<?> event) throws IOException {
        String schema;
        try (InputStream in = getClass().getResourceAsStream("/contracts/events/envelope.schema.json")) {
            schema = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new JsonSchemaValidator().validate("envelope", schema, mapper.writeValueAsString(event)).toString();
    }

    private Captured captureAudit(String actor, boolean job) {
        if (actor != null) {
            ActorHolder.set(ActorContext.parse(actor));
        } else {
            ActorHolder.clear();
        }
        LoyaltyHubProperties props = new LoyaltyHubProperties();
        props.setService("wallet");
        Captured c = new Captured();
        AuditPublisher audit = new AuditPublisher(c.writer(), new LhEventFactory(at(NOW), "wallet"), props);
        if (job) {
            audit.recordJob("WALLET", "MBR-000004", "Scadenza", null, Map.of("expired", 10));
        } else {
            audit.record("WALLET", "MBR-000004", AuditEntry.Action.ADJUST, "Accredito manuale di 200 PTS (GOODWILL)",
                    Map.of("balance", 12300), Map.of("balance", 12500));
        }
        return c;
    }

    /** Scrittore d'outbox che registra topic, chiave ed evento invece di scrivere sul DB. */
    private final class Captured {
        String topic;
        String key;
        LhEvent<?> event;

        LhEvent<?> event() {
            return event;
        }

        OutboxWriter writer() {
            return new OutboxWriter(null, mapper, new LoyaltyHubProperties()) {
                @Override
                public UUID write(String t, String k, LhEvent<?> e) {
                    topic = t;
                    key = k;
                    event = e;
                    return UUID.randomUUID();
                }
            };
        }
    }
}
