package io.loyaltyhub.hub;

import io.loyaltyhub.common.event.LhHeaders;
import io.loyaltyhub.common.kafka.LoopGuardException;
import io.loyaltyhub.common.kafka.NonRetryableEventException;
import io.loyaltyhub.hub.bus.HubInProcessBus;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-PLT §BUS — bus a eventi in-process dell'hub (ADR-024): stesse proprietà del trasporto Kafka che contano per il
 * modello (fan-out per gruppo consumer, asincronia, ordine, header), ritentativi e DLQ per tipo d'errore con gli stessi
 * header {@code lh-*} del recoverer di lh-common (docs/04 §5). Bus reale, consumatori finti; ritardi di 1 ms.
 */
class TestbookPltBusTest {

    private static final String TOPIC = "lh.facts.v1";
    private static final String DLQ = "lh.dlq.v1";

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/plt/bus.csv", numLinesToSkip = 1)
    void bus(String id, String description, String kase, String expected) throws Exception {
        assertThat(run(kase)).as("%s: %s", id, description).isEqualTo(expected);
    }

    private String run(String kase) throws Exception {
        long[] backoffs = kase.equals("backoff3") ? new long[]{1, 1, 1} : new long[]{1, 1};
        HubInProcessBus bus = new HubInProcessBus(DLQ, backoffs);
        List<String> dead = Collections.synchronizedList(new ArrayList<>());
        List<ConsumerRecord<String, String>> deadRecords = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(DLQ, "lh-insight", r -> {
            deadRecords.add(r);
            dead.add(header(r, LhHeaders.CONSUMER) + ":" + header(r, LhHeaders.ERROR_CODE) + ":"
                    + header(r, LhHeaders.ATTEMPTS) + ":" + header(r, LhHeaders.ERROR_RETRYABLE));
        });
        try {
            return switch (kase) {
                case "fanout" -> {
                    AtomicInteger a = new AtomicInteger();
                    AtomicInteger b = new AtomicInteger();
                    bus.subscribe(TOPIC, "lh-wallet", r -> a.incrementAndGet());
                    bus.subscribe(TOPIC, "lh-campaign", r -> b.incrementAndGet());
                    bus.publish(record("K1", "v"));
                    drain(bus);
                    yield "lh-wallet=" + a.get() + " lh-campaign=" + b.get();
                }
                case "noSubscribers" -> {
                    bus.publish(new ProducerRecord<>("lh.audit.v1", "K", "v"));
                    drain(bus);
                    yield "nessun errore";
                }
                case "order" -> {
                    List<String> seen = Collections.synchronizedList(new ArrayList<>());
                    bus.subscribe(TOPIC, "lh-wallet", r -> seen.add(r.value()));
                    List<String> sent = new ArrayList<>();
                    for (int i = 0; i < 50; i++) {
                        sent.add("v" + i);
                        bus.publish(record(i % 2 == 0 ? "K1" : "K2", "v" + i));
                    }
                    drain(bus);
                    yield seen.equals(sent) ? "ordine di pubblicazione" : seen.toString();
                }
                case "headers" -> {
                    List<String> types = new ArrayList<>();
                    bus.subscribe(TOPIC, "lh-wallet", r -> types.add(header(r, LhHeaders.TYPE) + " " + r.key()));
                    bus.publish(record("MBR-1", "v"));
                    drain(bus);
                    yield String.join(",", types);
                }
                case "async" -> {
                    CountDownLatch release = new CountDownLatch(1);
                    AtomicInteger done = new AtomicInteger();
                    bus.subscribe(TOPIC, "lh-wallet", r -> {
                        release.await(5, TimeUnit.SECONDS);
                        done.incrementAndGet();
                    });
                    bus.publish(record("K", "v"));
                    int duringPublish = done.get();
                    release.countDown();
                    drain(bus);
                    yield "durante la pubblicazione=" + duringPublish + " dopo=" + done.get();
                }
                case "retryable", "nonRetryable", "loopGuard", "json", "backoff3" -> {
                    AtomicInteger calls = new AtomicInteger();
                    bus.subscribe(TOPIC, "lh-wallet", r -> {
                        calls.incrementAndGet();
                        throw failure(kase);
                    });
                    bus.publish(record("MBR-9", "poison"));
                    drain(bus);
                    yield "tentativi=" + calls.get() + " dlq=" + dead;
                }
                case "recovers" -> {
                    AtomicInteger calls = new AtomicInteger();
                    bus.subscribe(TOPIC, "lh-wallet", r -> {
                        if (calls.incrementAndGet() < 3) {
                            throw new IllegalStateException("transitorio");
                        }
                    });
                    bus.publish(record("MBR-9", "v"));
                    drain(bus);
                    yield "tentativi=" + calls.get() + " dlq=" + dead;
                }
                case "dlqRecord" -> {
                    bus.subscribe(TOPIC, "lh-wallet", r -> {
                        throw new IllegalStateException("guasto");
                    });
                    bus.publish(record("MBR-9", "poison"));
                    drain(bus);
                    ConsumerRecord<String, String> d = deadRecords.get(0);
                    yield "chiave=" + d.key() + " valore=" + d.value() + " lh-type=" + header(d, LhHeaders.TYPE)
                            + " topic=" + header(d, LhHeaders.ORIGINAL_TOPIC);
                }
                case "otherContinues" -> {
                    List<String> ok = Collections.synchronizedList(new ArrayList<>());
                    bus.subscribe(TOPIC, "lh-wallet", r -> {
                        if (r.value().equals("poison")) {
                            throw new NonRetryableEventException("INVALID_EFFECT", "no");
                        }
                        ok.add("wallet:" + r.value());
                    });
                    bus.subscribe(TOPIC, "lh-campaign", r -> ok.add("campaign:" + r.value()));
                    bus.publish(record("MBR-9", "poison"));
                    bus.publish(record("MBR-9", "next"));
                    drain(bus);
                    yield String.join(" ", ok);
                }
                case "dlqConsumerFails" -> {
                    AtomicInteger dlqCalls = new AtomicInteger();
                    bus.subscribe(DLQ, "lh-rotto", r -> {
                        dlqCalls.incrementAndGet();
                        throw new IllegalStateException("anche la DLQ fallisce");
                    });
                    bus.subscribe(TOPIC, "lh-wallet", r -> {
                        throw new NonRetryableEventException("INVALID_EFFECT", "no");
                    });
                    bus.publish(record("MBR-9", "poison"));
                    drain(bus);
                    yield "tentativi del consumer DLQ=" + dlqCalls.get() + " voci DLQ=" + dead.size();
                }
                default -> throw new IllegalArgumentException(kase);
            };
        } finally {
            bus.close();
        }
    }

    private static RuntimeException failure(String kase) {
        return switch (kase) {
            case "nonRetryable" -> new NonRetryableEventException("COUPON_POOL_EMPTY", "pool esaurito");
            case "loopGuard" -> new LoopGuardException("lhhop 4");
            case "json" -> {
                try {
                    new tools.jackson.databind.ObjectMapper().readTree("{non json");
                    yield new IllegalStateException("atteso errore");
                } catch (tools.jackson.core.JacksonException e) {
                    yield e;
                }
            }
            default -> new IllegalStateException("guasto");
        };
    }

    private static ProducerRecord<String, String> record(String key, String value) {
        RecordHeaders h = new RecordHeaders();
        h.add(new RecordHeader(LhHeaders.TYPE, "io.loyaltyhub.fact.probe".getBytes(StandardCharsets.UTF_8)));
        return new ProducerRecord<>(TOPIC, null, key, value, h);
    }

    /** Attende che il thread di consegna (FIFO) abbia smaltito tutto: una barriera in coda. */
    private static void drain(HubInProcessBus bus) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        String topic = "tb.plt.barrier." + System.nanoTime();
        bus.subscribe(topic, "barrier", r -> latch.countDown());
        bus.publish(new ProducerRecord<>(topic, "b", "b"));
        assertThat(latch.await(10, TimeUnit.SECONDS)).as("barriera del bus").isTrue();
        // I record DLQ nati durante la consegna vengono accodati dopo la barriera: un secondo giro li smaltisce.
        CountDownLatch second = new CountDownLatch(1);
        bus.subscribe(topic + ".2", "barrier", r -> second.countDown());
        bus.publish(new ProducerRecord<>(topic + ".2", "b", "b"));
        assertThat(second.await(10, TimeUnit.SECONDS)).as("seconda barriera del bus").isTrue();
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        var h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
