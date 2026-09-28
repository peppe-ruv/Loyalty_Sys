package io.loyaltyhub.ingestion;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.event.LhHeaders;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.testsupport.ListenerGroups;
import io.loyaltyhub.testsupport.TopicReader;
import io.loyaltyhub.ingestion.domain.MemberRef;
import io.loyaltyhub.ingestion.infra.InboundEventRepository;
import io.loyaltyhub.ingestion.infra.InternalMappingRepository;
import io.loyaltyhub.ingestion.infra.MemberIndexRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ponte interno fatti → azioni e indice membri (docs/servizi/ingestion-service.md §4, §7; docs/05 §7), end-to-end su
 * Kafka: fatto su {@code lh.facts.v1} → azione su {@code lh.actions.v1} / DLQ. EmbeddedKafka + Zonky, senza Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@EmbeddedKafka(partitions = 1, topics = {"lh.facts.v1", "lh.actions.v1", "lh.audit.v1", "lh.dlq.v1"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InternalBridgeIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final TypeReference<LhEvent<JsonNode>> EVENT = new TypeReference<>() {
    };

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=ingestion");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired private ObjectMapper mapper;
    @Autowired private InternalMappingRepository mappings;
    @Autowired private InboundEventRepository inbound;
    @Autowired private MemberIndexRepository members;
    @Autowired private JdbcClient jdbc;
    @Autowired private KafkaListenerEndpointRegistry listeners;

    /** Si pubblica solo a gruppo {@code lh-ingestion} stabile, con le partizioni assegnate ({@link ListenerGroups}). */
    @BeforeAll
    void waitForListenerGroup() {
        ListenerGroups.awaitStable(listeners);
    }

    @BeforeEach
    void mappingsAsSeed() {
        // Formato del seed (seed/internal-mappings.json): nomi brevi.
        mappings.upsert("fact.tier.upgraded", "tier.upgraded", true);
        mappings.upsert("fact.member.registered", "member.registered", true);
    }

    @Test
    void mappedFactBecomesInternalActionWithSameCorrelationAndHopPlusOne() throws Exception {
        String memberId = "MBR-000002";
        LhEvent<JsonNode> fact = fact(LhEventTypes.Fact.TIER_UPGRADED, memberId, 0,
                Map.of("previousTier", "SILVER", "newTier", "GOLD"));
        {
            publish(fact);
            ConsumerRecord<String, String> rec = first(actions(List.of(fact.id()), r -> r.value().contains(fact.id())));
            assertThat(rec).as("azione ponte su lh.actions.v1").isNotNull();
            assertThat(rec.key()).isEqualTo(memberId);

            LhEvent<JsonNode> action = mapper.readValue(rec.value(), EVENT);
            assertThat(action.type()).isEqualTo(LhEventTypes.Action.TIER_UPGRADED);
            assertThat(action.source()).isEqualTo("urn:loyaltyhub:source:internal");
            assertThat(action.subject()).isEqualTo("member:" + memberId);
            assertThat(action.time()).isEqualTo(fact.time());
            assertThat(action.lhcorrelationid()).isEqualTo(fact.lhcorrelationid());
            assertThat(action.lhcausationid()).isEqualTo(fact.id());
            assertThat(action.hopOrZero()).isEqualTo(1);
            assertThat(action.data().path("newTier").asString()).isEqualTo("GOLD");

            var rows = inbound.search("ACCEPTED", "internal", "tier.upgraded", memberId, 10);
            assertThat(rows).anyMatch(r -> r.eventId().equals(action.id()));
        }
    }

    @Test
    void sameFactTwiceProducesOneAction() throws Exception {
        LhEvent<JsonNode> fact = fact(LhEventTypes.Fact.TIER_UPGRADED, "MBR-000003", 0, Map.of("newTier", "SILVER"));
        {
            RecordMetadata first = publish(fact);
            RecordMetadata again = publish(fact);
            // Barriera esatta anche per il doppione (stesso id, niente processed_event nuovo): offset confermati.
            ListenerGroups.awaitCommitted(listeners, List.of(first, again));
            List<ConsumerRecord<String, String>> bridged = actions(List.of(fact.id()), r -> r.value().contains(fact.id()));
            assertThat(bridged).as("azione ponte").isNotEmpty();
            assertThat(bridged).as("una sola azione malgrado il doppio invio").hasSize(1);
        }
    }

    @Test
    void disabledMappingConsumesFactWithoutAction() throws Exception {
        mappings.upsert("fact.tier.upgraded", "tier.upgraded", false);
        LhEvent<JsonNode> fact = fact(LhEventTypes.Fact.TIER_UPGRADED, "MBR-000004", 0, Map.of("newTier", "GOLD"));
        {
            publish(fact);
            waitFor(() -> processed(fact.id()) == 1);
            assertThat(first(actions(List.of(fact.id()), r -> r.value().contains(fact.id())))).isNull();
        }
    }

    @Test
    void unmappedFactIsIgnoredWithoutProcessedEvent() throws Exception {
        LhEvent<JsonNode> earned = fact(LhEventTypes.Fact.WALLET_POINTS_EARNED, "MBR-000002", 0, Map.of("amount", 100));
        LhEvent<JsonNode> marker = fact(LhEventTypes.Fact.TIER_UPGRADED, "MBR-000002", 0, Map.of("newTier", "GOLD"));
        publish(earned);
        publish(marker); // stessa partizione: quando il marcatore è elaborato, anche il precedente è passato
        waitFor(() -> processed(marker.id()) == 1);
        assertThat(processed(earned.id())).isZero();
    }

    @Test
    void hopThreeGoesToDlqWithLoopGuard() throws Exception {
        LhEvent<JsonNode> fact = fact(LhEventTypes.Fact.TIER_UPGRADED, "MBR-000005", 3, Map.of("newTier", "GOLD"));
        {
            RecordMetadata sent = publish(fact);
            // Il record finito in DLQ non scrive processed_event: la barriera è l'offset confermato dopo la DLQ.
            ListenerGroups.awaitCommitted(listeners, List.of(sent));
            ConsumerRecord<String, String> rec = first(new TopicReader(jdbc, mapper, "lh.dlq.v1")
                    .records(List.of(), r -> r.value().contains(fact.id())));
            assertThat(rec).as("record DLQ").isNotNull();
            assertThat(new String(rec.headers().lastHeader(LhHeaders.ERROR_CODE).value())).isEqualTo("LOOP_GUARD");
            assertThat(first(actions(List.of(), r -> r.value().contains(fact.id())))).isNull();
        }
    }

    @Test
    void memberFactsMaintainTheIndexAndStatusChangeKeepsIdentifiers() throws Exception {
        publish(fact(LhEventTypes.Fact.MEMBER_REGISTERED, "MBR-900001", 0,
                Map.of("email", "Nuovo.Socio@clubaurora.example", "externalId", "CRM-9001", "status", "ACTIVE")));
        waitFor(() -> members.findByMemberId("MBR-900001").isPresent());
        assertThat(members.findByEmail("nuovo.socio@clubaurora.example")).map(MemberRef::memberId).contains("MBR-900001");

        publish(fact(LhEventTypes.Fact.MEMBER_STATUS_CHANGED, "MBR-900001", 0,
                Map.of("previousStatus", "ACTIVE", "newStatus", "BLOCKED")));
        waitFor(() -> members.findByMemberId("MBR-900001").map(MemberRef::status).orElse("").equals("BLOCKED"));
        assertThat(members.findByExternalId("CRM-9001")).map(MemberRef::memberId).contains("MBR-900001");
    }

    @Test
    void mappingsApiListsTogglesAndRejectsForbiddenFamilies() {
        RestClient http = RestClient.create("http://localhost:" + port);
        String list = http.get().uri("/v1/internal-mappings").retrieve().body(String.class);
        assertThat(list).contains("fact.tier.upgraded");

        String updated = http.put().uri("/v1/internal-mappings/fact.member.registered")
                .header("X-LH-Actor", "ADMIN:giuseppe").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("enabled", false)).retrieve().body(String.class);
        assertThat(updated).contains("\"enabled\":false");
        assertThat(mappings.actionTypeFor("fact.member.registered")).isEmpty();

        int forbidden = http.put().uri("/v1/internal-mappings/fact.wallet.points.earned")
                .header("X-LH-Actor", "ADMIN:giuseppe").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("enabled", true)).exchange((req, res) -> res.getStatusCode().value());
        assertThat(forbidden).isEqualTo(422);

        int notAdmin = http.put().uri("/v1/internal-mappings/fact.tier.upgraded")
                .header("X-LH-Actor", "MARKETING:giulia").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("enabled", false)).exchange((req, res) -> res.getStatusCode().value());
        assertThat(notAdmin).isEqualTo(403);
    }

    // ---------- helper ----------

    private LhEvent<JsonNode> fact(String type, String memberId, int hop, Map<String, Object> data) {
        String id = Ulid.next(Clock.systemUTC());
        return new LhEvent<>(LhEvent.SPEC_VERSION, id, "urn:loyaltyhub:service:test", type, "member:" + memberId,
                Instant.now().truncatedTo(ChronoUnit.MILLIS), LhEvent.DATA_CONTENT_TYPE, null,
                LhEvent.TENANT, "COR-" + id, null, hop, null, mapper.valueToTree(data));
    }

    private RecordMetadata publish(LhEvent<JsonNode> event) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class))) {
            return producer.send(new ProducerRecord<>("lh.facts.v1", event.subject().substring("member:".length()),
                    mapper.writeValueAsString(event))).get();
        }
    }

    private long processed(String eventId) {
        return jdbc.sql("SELECT count(*) FROM processed_event WHERE event_id = ?").param(eventId).query(Long.class).single();
    }

    /**
     * Azioni su {@code lh.actions.v1} che soddisfano {@code match}, esatte ({@link TopicReader}): prima il servizio deve
     * aver elaborato {@code consumedFirst} ({@code processed_event}), poi il topic si legge fino in fondo a outbox
     * svuotato, senza consumer group né finestra di tempo. Un doppione resta visibile, un'azione assente è davvero assente.
     */
    private List<ConsumerRecord<String, String>> actions(List<String> consumedFirst,
                                                         Predicate<ConsumerRecord<String, String>> match) {
        return new TopicReader(jdbc, mapper, "lh.actions.v1").records(consumedFirst, match);
    }

    /** Il primo record nell'ordine del topic, {@code null} se non ce n'è. */
    private static ConsumerRecord<String, String> first(List<ConsumerRecord<String, String>> records) {
        return records.isEmpty() ? null : records.getFirst();
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        assertThat(condition.getAsBoolean()).as("condizione attesa entro 15 s").isTrue();
    }
}
