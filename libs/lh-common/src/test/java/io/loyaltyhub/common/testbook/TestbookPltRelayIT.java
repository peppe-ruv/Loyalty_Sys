package io.loyaltyhub.common.testbook;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.event.LhHeaders;
import io.loyaltyhub.common.event.LhJson;
import io.loyaltyhub.common.event.LhSource;
import io.loyaltyhub.common.inbox.IdempotentHandler;
import io.loyaltyhub.common.inbox.ProcessedEventCleanup;
import io.loyaltyhub.common.inbox.ProcessedEvents;
import io.loyaltyhub.common.kafka.LhKafkaConfiguration;
import io.loyaltyhub.common.kafka.LoopGuardException;
import io.loyaltyhub.common.kafka.LoyaltyHubProperties;
import io.loyaltyhub.common.kafka.NonRetryableEventException;
import io.loyaltyhub.common.metrics.LhMetrics;
import io.loyaltyhub.common.outbox.OutboxCleanup;
import io.loyaltyhub.common.outbox.OutboxRelay;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-PLT §REL, §ITX e §DLK — affidabilità di lh-common su Postgres reale e Kafka reale (docs/04 §5, ADR-008, RNF-03,
 * RNF-04, RNF-05, RNF-07): relay dell'outbox (ordine per chiave, lotti da 100, broker giù e poi su, guasto a metà lotto,
 * due relay concorrenti con {@code SKIP LOCKED}, pulizie), consumo idempotente transazionale e ritentativi/DLQ per tipo
 * d'errore con l'error handler vero di {@link LhKafkaConfiguration}.
 *
 * <p>SPEC-GAP Q-40: Kafka in-JVM (KRaft) e Postgres in-process (Zonky) al posto di Testcontainers. Chiavi e topic nuovi
 * a ogni riga: nessuna riga dipende da un'altra.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestbookPltRelayIT {

    private static final String EFFECTS = "lh.effects.v1";
    private static final String AUDIT = "lh.audit.v1";
    private static final String DLQ = "lh.dlq.v1";
    private static final AtomicLong SEQ = new AtomicLong();

    private final ObjectMapper mapper = LhJson.create();
    private final LoyaltyHubProperties props = new LoyaltyHubProperties();
    private final LhEventFactory events = new LhEventFactory(Clock.systemUTC(), "campaign");

    private EmbeddedPostgres pg;
    private EmbeddedKafkaKraftBroker broker;
    private String bootstrap;
    private JdbcClient jdbc;
    private TransactionTemplate tx;
    private OutboxWriter writer;
    private KafkaTemplate<String, String> template;

    @BeforeAll
    void up() throws Exception {
        props.setService("test");
        props.getConsumer().setRetryBackoffMs(new long[]{50, 50});
        pg = EmbeddedPostgres.builder().start();
        DataSource ds = pg.getPostgresDatabase();
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/common").load().migrate();
        jdbc = JdbcClient.create(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        broker = new EmbeddedKafkaKraftBroker(1, 2, "lh.actions.v1", EFFECTS, "lh.facts.v1", AUDIT, DLQ);
        broker.afterPropertiesSet();
        bootstrap = broker.getBrokersAsString();
        writer = new OutboxWriter(jdbc, mapper, props);
        template = new KafkaTemplate<>(producerFactory(bootstrap, false));
    }

    @AfterAll
    void down() throws Exception {
        if (template != null) {
            template.destroy();
        }
        if (broker != null) {
            broker.destroy();
        }
        if (pg != null) {
            pg.close();
        }
    }

    // ================= relay dell'outbox =================

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/plt/relay.csv", numLinesToSkip = 1)
    void relay(String id, String description, String kase, String expected) throws Exception {
        jdbc.sql("DELETE FROM outbox").update();
        assertThat(runRelay(kase)).as("%s: %s", id, description).isEqualTo(expected);
    }

    private String runRelay(String kase) throws Exception {
        String key = "MBR-REL-" + SEQ.incrementAndGet() + "-" + System.nanoTime();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxRelay relay = new OutboxRelay(jdbc, template, mapper, new LhMetrics(registry), 100);
        return switch (kase) {
            case "publish" -> {
                LhEvent<?> e = grant(key, 0);
                tx.executeWithoutResult(s -> writer.write(e));
                tx.executeWithoutResult(s -> relay.publishBatch());
                ConsumerRecord<String, String> r = pollOne(EFFECTS, key);
                yield "topic=" + r.topic() + " chiave=" + (key.equals(r.key()) ? "memberId" : r.key())
                        + " valore=" + (mapper.readTree(r.value()).path("id").asString().equals(e.id()) ? "envelope" : r.value())
                        + " pubblicata=" + (unpublished(key) == 0 && published(key) == 1);
            }
            case "headersChild" -> {
                LhEvent<Map<String, Object>> parent = new LhEvent<>("1.0", "PARENT-" + key, LhSource.source("ecommerce"),
                        LhEventTypes.Action.PURCHASE_COMPLETED, "member:" + key, java.time.Instant.now(), "application/json",
                        "urn:x:1", "aurora", "CORR-" + key, null, 2, "CARE:paolo.care", Map.of());
                LhEvent<?> e = events.childOf(parent, LhEventTypes.Effect.POINTS_GRANT, Map.of("amount", 1));
                tx.executeWithoutResult(s -> writer.write(e));
                tx.executeWithoutResult(s -> relay.publishBatch());
                ConsumerRecord<String, String> r = pollOne(EFFECTS, key);
                yield "lh-type=" + header(r, LhHeaders.TYPE) + " lh-correlation-id=" + ok(header(r, LhHeaders.CORRELATION_ID), "CORR-" + key)
                        + " lh-causation-id=" + ok(header(r, LhHeaders.CAUSATION_ID), "PARENT-" + key)
                        + " lh-hop=" + header(r, LhHeaders.HOP) + " lh-actor=" + header(r, LhHeaders.ACTOR)
                        + " content-type=" + header(r, "content-type");
            }
            case "headersRoot" -> {
                LhEvent<?> e = grant(key, 0);
                tx.executeWithoutResult(s -> writer.write(e));
                tx.executeWithoutResult(s -> relay.publishBatch());
                ConsumerRecord<String, String> r = pollOne(EFFECTS, key);
                yield "lh-causation-id=" + nz(header(r, LhHeaders.CAUSATION_ID)) + " lh-actor=" + nz(header(r, LhHeaders.ACTOR))
                        + " lh-hop=" + header(r, LhHeaders.HOP);
            }
            case "audit" -> {
                String auditKey = "WALLET:" + key;
                LhEvent<?> e = events.newRoot(LhEventTypes.Audit.ENTRY, auditKey, Map.of("action", "ADJUST"),
                        LhSource.service("wallet"), "CARE:paolo.care");
                tx.executeWithoutResult(s -> writer.write(props.getTopics().getAudit(), auditKey, e));
                tx.executeWithoutResult(s -> relay.publishBatch());
                ConsumerRecord<String, String> r = pollOne(AUDIT, auditKey);
                yield "topic=" + r.topic() + " chiave=" + (auditKey.equals(r.key()) ? "entityType:entityId" : r.key());
            }
            case "rollback" -> {
                try {
                    tx.executeWithoutResult(s -> {
                        writer.write(grant(key, 0));
                        throw new IllegalStateException("la scrittura di stato fallisce");
                    });
                } catch (IllegalStateException expected) {
                    // la transazione del cambiamento di stato è annullata
                }
                tx.executeWithoutResult(s -> relay.publishBatch());
                yield "outbox=" + rows(key) + " ricevuti=" + pollAll(EFFECTS, key, 1, Duration.ofSeconds(2)).size();
            }
            case "orderOneTx" -> {
                tx.executeWithoutResult(s -> {
                    for (int i = 0; i < 20; i++) {
                        writer.write(grant(key, i));
                    }
                });
                tx.executeWithoutResult(s -> relay.publishBatch());
                yield "ordine=" + seqs(pollAll(EFFECTS, key, 20, Duration.ofSeconds(20)));
            }
            case "orderAcrossTx" -> {
                for (int t = 0; t < 3; t++) {
                    int base = t * 5;
                    tx.executeWithoutResult(s -> {
                        for (int i = 0; i < 5; i++) {
                            writer.write(grant(key, base + i));
                        }
                    });
                }
                tx.executeWithoutResult(s -> relay.publishBatch());
                yield "ordine=" + seqs(pollAll(EFFECTS, key, 15, Duration.ofSeconds(20)));
            }
            case "batch99", "batch100", "batch101" -> {
                int n = Integer.parseInt(kase.substring(5));
                tx.executeWithoutResult(s -> {
                    for (int i = 0; i < n; i++) {
                        writer.write(grant(key, i));
                    }
                });
                tx.executeWithoutResult(s -> relay.publishBatch());
                long afterFirst = unpublished(key);
                tx.executeWithoutResult(s -> relay.publishBatch());
                yield "dopo un giro=" + (n - afterFirst) + " in attesa=" + afterFirst + " dopo due giri in attesa=" + unpublished(key);
            }
            case "brokerDown" -> {
                tx.executeWithoutResult(s -> writer.write(grant(key, 0)));
                KafkaTemplate<String, String> dead = new KafkaTemplate<>(producerFactory("localhost:1", true));
                OutboxRelay deadRelay = new OutboxRelay(jdbc, dead, mapper, new LhMetrics(new SimpleMeterRegistry()), 100);
                String error;
                try {
                    tx.executeWithoutResult(s -> deadRelay.publishBatch());
                    error = "nessuna";
                } catch (OutboxRelay.OutboxPublishException e) {
                    error = "OutboxPublishException";
                } finally {
                    dead.destroy();
                }
                yield "eccezione=" + error + " in attesa=" + unpublished(key);
            }
            case "brokerBack" -> {
                tx.executeWithoutResult(s -> writer.write(grant(key, 0)));
                KafkaTemplate<String, String> dead = new KafkaTemplate<>(producerFactory("localhost:1", true));
                OutboxRelay deadRelay = new OutboxRelay(jdbc, dead, mapper, new LhMetrics(new SimpleMeterRegistry()), 100);
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        tx.executeWithoutResult(s -> deadRelay.publishBatch());
                    } catch (OutboxRelay.OutboxPublishException expected) {
                        // broker ancora giù
                    }
                }
                dead.destroy();
                tx.executeWithoutResult(s -> relay.publishBatch());
                yield "in attesa=" + unpublished(key) + " ricevuti=" + pollAll(EFFECTS, key, 1, Duration.ofSeconds(10)).size();
            }
            case "midBatch" -> {
                tx.executeWithoutResult(s -> {
                    for (int i = 0; i < 5; i++) {
                        writer.write(grant(key, i));
                    }
                });
                AtomicInteger sends = new AtomicInteger();
                KafkaTemplate<String, String> flaky = new KafkaTemplate<>(producerFactory(bootstrap, false)) {
                    @Override
                    public CompletableFuture<SendResult<String, String>> send(ProducerRecord<String, String> record) {
                        if (sends.incrementAndGet() == 3) {
                            return CompletableFuture.failedFuture(new IllegalStateException("broker perso a metà lotto"));
                        }
                        return super.send(record);
                    }
                };
                OutboxRelay flakyRelay = new OutboxRelay(jdbc, flaky, mapper, new LhMetrics(new SimpleMeterRegistry()), 100);
                try {
                    tx.executeWithoutResult(s -> flakyRelay.publishBatch());
                } catch (OutboxRelay.OutboxPublishException expected) {
                    // il lotto è annullato: nessuna riga marcata pubblicata
                }
                long afterFailure = unpublished(key);
                tx.executeWithoutResult(s -> flakyRelay.publishBatch());
                flaky.destroy();
                List<ConsumerRecord<String, String>> delivered = pollAll(EFFECTS, key, 7, Duration.ofSeconds(20));
                // Il consumer idempotente vede ogni evento una sola volta, nell'ordine di scrittura.
                ProcessedEvents processed = new ProcessedEvents(jdbc);
                IdempotentHandler idempotent = new IdempotentHandler(processed);
                List<Integer> applied = new ArrayList<>();
                for (ConsumerRecord<String, String> r : delivered) {
                    LhEvent<JsonNode> ev = event(r.value());
                    tx.executeWithoutResult(s -> idempotent.handle("lh-test-" + key, ev,
                            e2 -> applied.add(e2.data().path("seq").asInt())));
                }
                yield "in attesa dopo il guasto=" + afterFailure + " consegne=" + delivered.size() + " elaborati="
                        + applied.stream().map(String::valueOf).collect(Collectors.joining(" "));
            }
            case "twoRelays" -> {
                tx.executeWithoutResult(s -> {
                    for (int i = 0; i < 200; i++) {
                        writer.write(grant(key, i));
                    }
                });
                OutboxRelay other = new OutboxRelay(jdbc, template, mapper, new LhMetrics(new SimpleMeterRegistry()), 100);
                CountDownLatch start = new CountDownLatch(1);
                ExecutorService pool = Executors.newFixedThreadPool(2);
                try {
                    List<Future<?>> fs = new ArrayList<>();
                    for (OutboxRelay r : List.of(relay, other)) {
                        fs.add(pool.submit(() -> {
                            start.await();
                            tx.executeWithoutResult(s -> r.publishBatch());
                            return null;
                        }));
                    }
                    start.countDown();
                    for (Future<?> f : fs) {
                        f.get();
                    }
                } finally {
                    pool.shutdownNow();
                }
                List<ConsumerRecord<String, String>> got = pollAll(EFFECTS, key, 200, Duration.ofSeconds(30));
                long distinct = got.stream().map(r -> mapper.readTree(r.value()).path("id").asString()).distinct().count();
                yield "in attesa=" + unpublished(key) + " messaggi=" + got.size() + " distinti=" + distinct;
            }
            case "empty" -> {
                tx.executeWithoutResult(s -> relay.publishBatch());
                yield "in attesa=" + jdbc.sql("SELECT count(*) FROM outbox").query(Long.class).single();
            }
            case "pendingGauge" -> {
                tx.executeWithoutResult(s -> {
                    for (int i = 0; i < 3; i++) {
                        writer.write(grant(key, i));
                    }
                });
                double before = registry.get("lh_outbox_pending").gauge().value();
                tx.executeWithoutResult(s -> relay.publishBatch());
                double after = registry.get("lh_outbox_pending").gauge().value();
                yield "prima=" + before + " dopo=" + after;
            }
            case "publishedMetric" -> {
                tx.executeWithoutResult(s -> {
                    writer.write(grant(key, 0));
                    writer.write(grant(key, 1));
                });
                tx.executeWithoutResult(s -> relay.publishBatch());
                yield "lh_events_published_total=" + registry.get("lh_events_published_total")
                        .tag("type", LhEventTypes.Effect.POINTS_GRANT).counter().count();
            }
            // ---- pulizie (RNF-07, docs/06 §4) ----
            case "outboxCleanOld", "outboxCleanRecent", "outboxCleanUnpublished" -> {
                String set = switch (kase) {
                    case "outboxCleanOld" -> "published_at = now() - interval '24 hours 1 minute'";
                    case "outboxCleanRecent" -> "published_at = now() - interval '23 hours 59 minutes'";
                    default -> "created_at = now() - interval '10 days', published_at = NULL";
                };
                tx.executeWithoutResult(s -> writer.write(grant(key, 0)));
                jdbc.sql("UPDATE outbox SET " + set + " WHERE msg_key = ?").param(key).update();
                new OutboxCleanup(jdbc).purgePublished();
                yield "riga " + (rows(key) == 1 ? "conservata" : "cancellata");
            }
            case "processedOld", "processedRecent" -> {
                String interval = kase.equals("processedOld") ? "14 days 1 minute" : "13 days 23 hours";
                jdbc.sql("INSERT INTO processed_event (consumer, event_id, processed_at) VALUES (?, ?, now() - interval '"
                        + interval + "')").params("lh-test", key).update();
                new ProcessedEventCleanup(jdbc, 14).purgeOld();
                long left = jdbc.sql("SELECT count(*) FROM processed_event WHERE event_id = ?").param(key).query(Long.class).single();
                yield "riga " + (left == 1 ? "conservata" : "cancellata");
            }
            // ---- consumo idempotente transazionale (docs/06 §1, RNF-03) ----
            case "idmRollback" -> {
                ProcessedEvents processed = new ProcessedEvents(jdbc);
                IdempotentHandler idempotent = new IdempotentHandler(processed);
                LhEvent<JsonNode> ev = event(mapper.writeValueAsString(grant(key, 0)));
                try {
                    tx.executeWithoutResult(s -> idempotent.handle("lh-test", ev, e -> {
                        writer.write(events.childOf(e, LhEventTypes.Fact.WALLET_POINTS_EARNED, Map.of("amount", 1)));
                        throw new IllegalStateException("guasto dopo la scrittura dell'effetto");
                    }));
                } catch (IllegalStateException expected) {
                    // la transazione del consumer è annullata
                }
                String afterFailure = "processed=" + (processed.isProcessed("lh-test", ev.id()) ? 1 : 0) + " outbox=" + rows(key);
                tx.executeWithoutResult(s -> idempotent.handle("lh-test", ev, e ->
                        writer.write(events.childOf(e, LhEventTypes.Fact.WALLET_POINTS_EARNED, Map.of("amount", 1)))));
                yield "dopo il guasto " + afterFailure + "; dopo il ritentativo processed="
                        + (processed.isProcessed("lh-test", ev.id()) ? 1 : 0) + " outbox=" + rows(key);
            }
            case "idmDuplicate" -> {
                IdempotentHandler idempotent = new IdempotentHandler(new ProcessedEvents(jdbc));
                LhEvent<JsonNode> ev = event(mapper.writeValueAsString(grant(key, 0)));
                List<Boolean> outcomes = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    outcomes.add(tx.execute(s -> idempotent.handle("lh-test", ev, e ->
                            writer.write(events.childOf(e, LhEventTypes.Fact.WALLET_POINTS_EARNED, Map.of("amount", 1))))));
                }
                yield "esiti=" + outcomes + " effetti=" + rows(key);
            }
            case "idmConcurrent" -> {
                IdempotentHandler idempotent = new IdempotentHandler(new ProcessedEvents(jdbc));
                LhEvent<JsonNode> ev = event(mapper.writeValueAsString(grant(key, 0)));
                CountDownLatch start = new CountDownLatch(1);
                ExecutorService pool = Executors.newFixedThreadPool(2);
                List<Future<Boolean>> fs = new ArrayList<>();
                try {
                    for (int i = 0; i < 2; i++) {
                        fs.add(pool.submit(() -> {
                            start.await();
                            return tx.execute(s -> idempotent.handle("lh-test", ev, e -> {
                                writer.write(events.childOf(e, LhEventTypes.Fact.WALLET_POINTS_EARNED, Map.of("amount", 1)));
                                pause(300);
                            }));
                        }));
                    }
                    start.countDown();
                    int executed = 0;
                    for (Future<Boolean> f : fs) {
                        executed += f.get() ? 1 : 0;
                    }
                    yield "eseguiti=" + executed + " effetti=" + rows(key);
                } finally {
                    pool.shutdownNow();
                }
            }
            default -> throw new IllegalArgumentException(kase);
        };
    }

    // ================= ritentativi e DLQ con l'error handler vero =================

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/plt/dlq-kafka.csv", numLinesToSkip = 1)
    void dlq(String id, String description, String error, int failures, String backoffs, String expected) throws Exception {
        String topic = "tb.plt.dlq." + SEQ.incrementAndGet();
        broker.addTopics(topic);
        String key = "MBR-DLQ-" + SEQ.incrementAndGet() + "-" + System.nanoTime();
        String group = "lh-tb-" + SEQ.incrementAndGet();
        LoyaltyHubProperties p = new LoyaltyHubProperties();
        p.setService("tb");
        p.getConsumer().setRetryBackoffMs(java.util.Arrays.stream(backoffs.split(" ")).mapToLong(Long::parseLong).toArray());
        LhKafkaConfiguration cfg = new LhKafkaConfiguration(p, bootstrap);
        KafkaTemplate<String, String> dlqTemplate = cfg.kafkaTemplate(cfg.lhProducerFactory());
        @SuppressWarnings("unchecked")
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                (ConcurrentKafkaListenerContainerFactory<String, String>) cfg.lhKafkaListenerContainerFactory(
                        cfg.lhConsumerFactory(), cfg.lhErrorHandler(dlqTemplate, new LhMetrics(new SimpleMeterRegistry())), true);
        ConcurrentMessageListenerContainer<String, String> container = factory.createContainer(topic);
        container.getContainerProperties().setGroupId(group);
        Map<String, AtomicInteger> invocations = new HashMap<>();
        List<String> processed = java.util.Collections.synchronizedList(new ArrayList<>());
        container.setupMessageListener((AcknowledgingMessageListener<String, String>) (record, ack) -> {
            int n = invocations.computeIfAbsent(record.value(), k -> new AtomicInteger()).incrementAndGet();
            if (record.value().startsWith("poison") && n <= failures) {
                throw failure(error);
            }
            processed.add(record.value());
            ack.acknowledge();
        });
        container.start();
        try {
            template.send(new ProducerRecord<>(topic, null, key, "poison", List.of(
                    new org.apache.kafka.common.header.internals.RecordHeader(LhHeaders.TYPE,
                            "io.loyaltyhub.fact.probe".getBytes(StandardCharsets.UTF_8))))).get();
            template.send(topic, key, "next").get();
            long deadline = System.currentTimeMillis() + 20_000;
            while (!processed.contains("next") && System.currentTimeMillis() < deadline) {
                pause(50);
            }
            List<ConsumerRecord<String, String>> deadLetters = pollAll(DLQ, key, 1, Duration.ofSeconds(5));
            ConsumerRecord<String, String> dead = deadLetters.isEmpty() ? null : deadLetters.get(0);
            String got = "invocazioni=" + invocations.getOrDefault("poison", new AtomicInteger()).get()
                    + " dlq=" + (deadLetters.isEmpty() ? "no" : deadLetters.size() == 1 ? "si" : String.valueOf(deadLetters.size()));
            if (dead != null) {
                got += " tentativi=" + header(dead, LhHeaders.ATTEMPTS) + " codice=" + header(dead, LhHeaders.ERROR_CODE)
                        + " ritentabile=" + header(dead, LhHeaders.ERROR_RETRYABLE)
                        + " topic=" + (topic.equals(header(dead, LhHeaders.ORIGINAL_TOPIC)) ? "origine" : header(dead, LhHeaders.ORIGINAL_TOPIC))
                        + " consumer=" + (group.equals(header(dead, LhHeaders.CONSUMER)) ? "gruppo" : header(dead, LhHeaders.CONSUMER))
                        + " chiave=" + (key.equals(dead.key()) ? "originale" : dead.key())
                        + " valore=" + dead.value() + " lh-type=" + header(dead, LhHeaders.TYPE);
            }
            got += " successivo=" + (processed.contains("next") ? "elaborato" : "fermo");
            assertThat(got).as("%s: %s", id, description).isEqualTo(expected);
        } finally {
            container.stop();
            dlqTemplate.destroy();
        }
    }

    private RuntimeException failure(String error) {
        return switch (error) {
            case "state" -> new IllegalStateException("guasto");
            case "nonRetryable" -> new NonRetryableEventException("COUPON_POOL_EMPTY", "pool esaurito");
            case "loopGuard" -> new LoopGuardException("lhhop 4");
            case "json" -> {
                try {
                    mapper.readTree("{non json");
                    yield new IllegalStateException("atteso errore di parsing");
                } catch (tools.jackson.core.JacksonException e) {
                    yield e;
                }
            }
            case "classCast" -> new ClassCastException("tipo inatteso");
            case "wrapped" -> new IllegalStateException("involucro", new NonRetryableEventException("TEMPLATE_NOT_FOUND", "assente"));
            default -> throw new IllegalArgumentException(error);
        };
    }

    // ================= supporto =================

    private LhEvent<Map<String, Object>> grant(String key, int seq) {
        return events.newRoot(LhEventTypes.Effect.POINTS_GRANT, "member:" + key, Map.of("seq", seq, "amount", 10),
                LhSource.service("campaign"), null);
    }

    private LhEvent<JsonNode> event(String json) {
        return mapper.readValue(json, new tools.jackson.core.type.TypeReference<LhEvent<JsonNode>>() {
        });
    }

    private long unpublished(String key) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE msg_key = ? AND published_at IS NULL").param(key).query(Long.class).single();
    }

    private long published(String key) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE msg_key = ? AND published_at IS NOT NULL").param(key).query(Long.class).single();
    }

    private long rows(String key) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE msg_key = ?").param(key).query(Long.class).single();
    }

    private String seqs(List<ConsumerRecord<String, String>> records) {
        List<Integer> seq = records.stream().map(r -> mapper.readTree(r.value()).path("data").path("seq").asInt()).toList();
        List<Integer> expected = java.util.stream.IntStream.range(0, seq.size()).boxed().toList();
        return seq.equals(expected) ? "0.." + (seq.size() - 1) : seq.toString();
    }

    private static String ok(String actual, String expected) {
        return expected.equals(actual) ? "ok" : String.valueOf(actual);
    }

    private static String nz(String v) {
        return v == null ? "assente" : v;
    }

    private ConsumerRecord<String, String> pollOne(String topic, String key) {
        List<ConsumerRecord<String, String>> got = pollAll(topic, key, 1, Duration.ofSeconds(20));
        assertThat(got).as("messaggio con chiave %s su %s", key, topic).isNotEmpty();
        return got.get(0);
    }

    /** Legge dall'inizio del topic i record con quella chiave, fino a {@code expected} o allo scadere del tempo. */
    private List<ConsumerRecord<String, String>> pollAll(String topic, String key, int expected, Duration timeout) {
        List<ConsumerRecord<String, String>> out = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG, "tb-reader-" + SEQ.incrementAndGet(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            while (out.size() < expected && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(200))) {
                    if (key.equals(r.key())) {
                        out.add(r);
                    }
                }
            }
            // Nessun record in più oltre l'atteso (duplicati visibili): un ultimo giro breve.
            long extra = System.currentTimeMillis() + 300;
            while (System.currentTimeMillis() < extra) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(100))) {
                    if (key.equals(r.key())) {
                        out.add(r);
                    }
                }
            }
        }
        return out;
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        var h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private static DefaultKafkaProducerFactory<String, String> producerFactory(String bootstrap, boolean failFast) {
        Map<String, Object> cfg = new HashMap<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true));
        if (failFast) {
            cfg.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 1500);
            cfg.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 1500);
            cfg.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000);
            cfg.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
        }
        return new DefaultKafkaProducerFactory<>(cfg);
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings("unused")
    private static <T> boolean any(List<T> list, Predicate<T> p) {
        return list.stream().anyMatch(p);
    }
}
