package io.loyaltyhub.common.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.event.LhHeaders;
import io.loyaltyhub.common.event.LhJson;
import io.loyaltyhub.common.event.LhSource;
import io.loyaltyhub.common.inbox.IdempotentHandler;
import io.loyaltyhub.common.inbox.ProcessedEvents;
import io.loyaltyhub.common.kafka.LhKafkaConfiguration;
import io.loyaltyhub.common.kafka.LoyaltyHubProperties;
import io.loyaltyhub.common.metrics.LhMetrics;
import io.loyaltyhub.common.outbox.OutboxRelay;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test d'integrazione del nucleo di {@code lh-common} (accettazione M0, docs/12): outbox → Kafka,
 * nessuna perdita a Kafka irraggiungibile, consumo idempotente, DLQ dopo 3 tentativi.
 *
 * <p>SPEC-GAP Q-40: la specifica indica Testcontainers; in questo ambiente il pull delle immagini
 * Docker è negato dalla policy del proxy, quindi si usano Kafka in-JVM (EmbeddedKafka KRaft) e
 * Postgres reale in-process (Zonky), entrambi da Maven Central. Il comportamento verificato è lo stesso.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LhCommonInfraIT {

    private static final String ACTIONS = "lh.actions.v1";
    private static final String EFFECTS = "lh.effects.v1";
    private static final String DLQ = "lh.dlq.v1";
    /** Topic dedicato al test DLQ, così nessun altro test vi pubblica (isolamento). */
    private static final String DLQ_INPUT = "dlq.probe";

    private EmbeddedPostgres pg;
    private EmbeddedKafkaKraftBroker broker;
    private DataSource ds;
    private JdbcClient jdbc;
    private String bootstrap;

    private final ObjectMapper mapper = LhJson.create();
    private final LoyaltyHubProperties props = new LoyaltyHubProperties();
    private final LhMetrics metrics = new LhMetrics(new SimpleMeterRegistry());
    private final LhEventFactory events = new LhEventFactory(Clock.systemUTC(), "ingestion");

    private OutboxWriter outboxWriter;
    private OutboxRelay relay;
    private IdempotentHandler idempotent;
    private ProcessedEvents processedEvents;
    private KafkaTemplate<String, String> template;
    private TransactionTemplate tx;

    @BeforeAll
    void up() throws Exception {
        props.setService("test");
        pg = EmbeddedPostgres.builder().start();
        ds = pg.getPostgresDatabase();
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(ds);

        broker = new EmbeddedKafkaKraftBroker(1, 1, ACTIONS, EFFECTS, "lh.facts.v1", "lh.audit.v1", DLQ, DLQ_INPUT);
        broker.afterPropertiesSet();
        bootstrap = broker.getBrokersAsString();

        template = new KafkaTemplate<>(producerFactory(bootstrap, false));
        outboxWriter = new OutboxWriter(jdbc, mapper, props);
        processedEvents = new ProcessedEvents(jdbc);
        idempotent = new IdempotentHandler(processedEvents);
        relay = new OutboxRelay(jdbc, template, mapper, metrics, 100);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @org.junit.jupiter.api.BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE outbox, processed_event").update();
    }

    @AfterAll
    void down() throws Exception {
        if (broker != null) {
            broker.destroy();
        }
        if (pg != null) {
            pg.close();
        }
    }

    @Test
    void outboxRelayPublishesToKafkaWithKeyAndHeaders() {
        LhEvent<Map<String, Object>> action = events.newRoot(
                LhEventTypes.Action.PURCHASE_COMPLETED, "member:MBR-000003",
                Map.of("orderId", "ORD-1", "amount", 130), LhSource.source("ecommerce"), null);

        tx.executeWithoutResult(s -> outboxWriter.write(action));
        assertThat(unpublishedCount(action.type())).isEqualTo(1);

        // Il relay è istanziato a mano (non è un proxy Spring): gli forniamo la transazione col tx template,
        // così SELECT ... FOR UPDATE e UPDATE condividono la stessa connessione.
        tx.executeWithoutResult(s -> relay.publishBatch());

        try (KafkaConsumer<String, String> consumer = consumer("relay-test")) {
            consumer.subscribe(List.of(ACTIONS));
            ConsumerRecord<String, String> rec = poll(consumer, r -> r.key().equals("MBR-000003"));
            assertThat(rec).as("messaggio pubblicato sul topic").isNotNull();
            assertThat(rec.key()).isEqualTo("MBR-000003");
            assertThat(header(rec, LhHeaders.TYPE)).isEqualTo(LhEventTypes.Action.PURCHASE_COMPLETED);
            assertThat(header(rec, LhHeaders.CORRELATION_ID)).isEqualTo(action.id());
        }
        assertThat(unpublishedCount(action.type())).isZero();
    }

    @Test
    void noLossWhenBrokerUnreachable() {
        LhEvent<Map<String, Object>> action = events.newRoot(
                LhEventTypes.Action.APP_LOGIN_DAILY, "member:MBR-000009",
                Map.of("platform", "WEB"), LhSource.source("app"), null);
        tx.executeWithoutResult(s -> outboxWriter.write(action));

        // Relay verso un broker irraggiungibile: la pubblicazione fallisce, la riga resta in outbox.
        KafkaTemplate<String, String> deadTemplate = new KafkaTemplate<>(producerFactory("localhost:1", true));
        OutboxRelay deadRelay = new OutboxRelay(jdbc, deadTemplate, mapper, metrics, 100);
        assertThatThrownBy(deadRelay::publishBatch).isInstanceOf(OutboxRelay.OutboxPublishException.class);
        assertThat(unpublishedForKey("MBR-000009")).isEqualTo(1);
        deadTemplate.destroy();

        // Al ritorno del broker, la stessa riga viene finalmente pubblicata: nessuna perdita.
        tx.executeWithoutResult(s -> relay.publishBatch());
        assertThat(unpublishedForKey("MBR-000009")).isZero();
        try (KafkaConsumer<String, String> consumer = consumer("noloss-test")) {
            consumer.subscribe(List.of(ACTIONS));
            assertThat(poll(consumer, r -> r.key().equals("MBR-000009"))).isNotNull();
        }
    }

    @Test
    void idempotentConsumerRunsLogicOnce() {
        String eventId = "01J8ZK3V7Q2M9T4B6N8R0IDEMP";
        LhEvent<com.fasterxml.jackson.databind.JsonNode> event = asJsonNode(events.newRoot(
                LhEventTypes.Action.PURCHASE_COMPLETED, "member:MBR-000004",
                Map.of("orderId", "ORD-IDEMP"), LhSource.source("ecommerce"), null), eventId);

        // La logica scrive un effetto nell'outbox: eseguirla due volte non deve duplicarlo.
        Runnable consume = () -> {
            boolean first = idempotent.handle("wallet", event, e ->
                    outboxWriter.write(events.childOf(e, LhEventTypes.Effect.POINTS_GRANT,
                            Map.of("effectId", "EFF-1", "amount", 130))));
            // seconda invocazione nello stesso test: sotto
            assertThat(first).isTrue();
        };
        tx.executeWithoutResult(s -> consume.run());

        boolean secondTime = tx.execute(s -> idempotent.handle("wallet", event, e ->
                outboxWriter.write(events.childOf(e, LhEventTypes.Effect.POINTS_GRANT,
                        Map.of("effectId", "EFF-DUP", "amount", 999)))));
        assertThat(secondTime).as("il duplicato non riesegue la logica").isFalse();

        assertThat(processedEvents.isProcessed("wallet", eventId)).isTrue();
        Long effects = jdbc.sql("SELECT count(*) FROM outbox WHERE type = ? AND payload->>'lhcausationid' = ?")
                .params(LhEventTypes.Effect.POINTS_GRANT, eventId).query(Long.class).single();
        assertThat(effects).as("un solo effetto malgrado il doppio invio").isEqualTo(1);
    }

    @Test
    void failingHandlerReachesDlqAfterThreeAttempts() throws Exception {
        DefaultErrorHandler errorHandler = new LhKafkaConfiguration(props, bootstrap)
                .lhErrorHandler(template, metrics);

        final int[] attempts = {0};
        ContainerProperties containerProps = new ContainerProperties(DLQ_INPUT);
        containerProps.setGroupId("dlq-test");
        containerProps.setMessageListener((MessageListener<String, String>) record -> {
            attempts[0]++;
            throw new IllegalStateException("boom");
        });
        containerProps.setAckMode(ContainerProperties.AckMode.RECORD);
        KafkaMessageListenerContainer<String, String> container =
                new KafkaMessageListenerContainer<>(consumerFactoryFor("dlq-test"), containerProps);
        container.setCommonErrorHandler(errorHandler);
        container.start();
        try {
            template.send(DLQ_INPUT, "MBR-000007", "{\"type\":\"boom\"}").get();

            try (KafkaConsumer<String, String> consumer = consumer("dlq-reader")) {
                consumer.subscribe(List.of(DLQ));
                ConsumerRecord<String, String> dead = poll(consumer, r -> r.key().equals("MBR-000007"));
                assertThat(dead).as("messaggio finito in DLQ").isNotNull();
                assertThat(header(dead, LhHeaders.ERROR_CODE)).isEqualTo("IllegalStateException");
            }
            // 3 tentativi (1 + 2 ritenti) prima della DLQ.
            assertThat(attempts[0]).isEqualTo(3);
        } finally {
            container.stop();
        }
    }

    // ---------- helper ----------

    private long unpublishedCount(String type) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE type = ? AND published_at IS NULL")
                .param(type).query(Long.class).single();
    }

    private long unpublishedForKey(String key) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE msg_key = ? AND published_at IS NULL")
                .param(key).query(Long.class).single();
    }

    private ProducerFactory<String, String> producerFactory(String bootstrap, boolean failFast) {
        Map<String, Object> cfg = new java.util.HashMap<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true));
        if (failFast) {
            cfg.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);
            cfg.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 2000);
            cfg.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000);
            cfg.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
        }
        return new DefaultKafkaProducerFactory<>(cfg);
    }

    private org.springframework.kafka.core.ConsumerFactory<String, String> consumerFactoryFor(String group) {
        return new org.springframework.kafka.core.DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
    }

    private KafkaConsumer<String, String> consumer(String group) {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
    }

    private ConsumerRecord<String, String> poll(KafkaConsumer<String, String> consumer,
                                                Predicate<ConsumerRecord<String, String>> match) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> recs = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> r : recs) {
                if (match.test(r)) {
                    return r;
                }
            }
        }
        return null;
    }

    private String header(ConsumerRecord<String, String> rec, String name) {
        var h = rec.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private LhEvent<com.fasterxml.jackson.databind.JsonNode> asJsonNode(LhEvent<?> event, String forcedId) {
        LhEvent<?> withId = new LhEvent<>(event.specversion(), forcedId, event.source(), event.type(),
                event.subject(), event.time(), event.datacontenttype(), event.dataschema(), event.lhtenant(),
                forcedId, event.lhcausationid(), event.lhhop(), event.lhactor(), event.data());
        com.fasterxml.jackson.databind.JsonNode data = mapper.valueToTree(withId.data());
        return new LhEvent<>(withId.specversion(), withId.id(), withId.source(), withId.type(), withId.subject(),
                withId.time(), withId.datacontenttype(), withId.dataschema(), withId.lhtenant(),
                withId.lhcorrelationid(), withId.lhcausationid(), withId.lhhop(), withId.lhactor(), data);
    }
}
