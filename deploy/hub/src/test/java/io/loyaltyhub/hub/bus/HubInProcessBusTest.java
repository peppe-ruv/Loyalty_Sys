package io.loyaltyhub.hub.bus;

import io.loyaltyhub.common.event.LhHeaders;
import io.loyaltyhub.common.kafka.NonRetryableEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Bus in-process (ADR-024) con la DLQ di M7.3: header conservati, ritentativi, record DLQ con gli header {@code lh-*}. */
class HubInProcessBusTest {

    @Test
    void failingConsumerEndsOnTheDlqTopicWithLhHeadersAfterThreeAttempts() throws Exception {
        try (HubInProcessBus bus = new HubInProcessBus("lh.dlq.v1", new long[]{50L, 50L})) {
            AtomicInteger attempts = new AtomicInteger();
            BlockingQueue<ConsumerRecord<String, String>> dead = new LinkedBlockingQueue<>();
            bus.subscribe("lh.actions.v1", "lh-campaign", r -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("boom");
            });
            bus.subscribe("lh.dlq.v1", "lh-insight", dead::add);

            ProducerRecord<String, String> record = new ProducerRecord<>("lh.actions.v1", "MBR-000002", "{\"id\":\"E1\"}");
            record.headers().add(LhHeaders.TYPE, "io.loyaltyhub.action.app.login.daily".getBytes(StandardCharsets.UTF_8));
            bus.publish(record);

            ConsumerRecord<String, String> dlq = dead.poll(5, TimeUnit.SECONDS);
            assertThat(dlq).isNotNull();
            assertThat(attempts.get()).isEqualTo(3);
            assertThat(dlq.key()).isEqualTo("MBR-000002");
            assertThat(dlq.value()).isEqualTo("{\"id\":\"E1\"}");
            assertThat(header(dlq, LhHeaders.TYPE)).isEqualTo("io.loyaltyhub.action.app.login.daily");
            assertThat(header(dlq, LhHeaders.ORIGINAL_TOPIC)).isEqualTo("lh.actions.v1");
            assertThat(header(dlq, LhHeaders.CONSUMER)).isEqualTo("lh-campaign");
            assertThat(header(dlq, LhHeaders.ERROR_CODE)).isEqualTo("IllegalStateException");
            assertThat(header(dlq, LhHeaders.ATTEMPTS)).isEqualTo("3");
        }
    }

    @Test
    void nonRetryableErrorGoesStraightToDlqAndDlqFailuresDoNotLoop() throws Exception {
        try (HubInProcessBus bus = new HubInProcessBus("lh.dlq.v1", new long[]{50L, 50L})) {
            AtomicInteger attempts = new AtomicInteger();
            AtomicInteger dlqDeliveries = new AtomicInteger();
            bus.subscribe("lh.actions.v1", "lh-campaign", r -> {
                attempts.incrementAndGet();
                throw new NonRetryableEventException("DEMO_POISON", "avvelenata");
            });
            // Un consumer della DLQ che fallisce: niente nuovo record DLQ (niente ciclo).
            bus.subscribe("lh.dlq.v1", "lh-insight", r -> {
                dlqDeliveries.incrementAndGet();
                throw new IllegalStateException("db giù");
            });
            bus.publish(new ProducerRecord<>("lh.actions.v1", "MBR-000002", "{}"));

            long deadline = System.currentTimeMillis() + 5_000;
            while (dlqDeliveries.get() < 3 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            Thread.sleep(700);
            assertThat(attempts.get()).as("non ritentabile: un solo tentativo").isEqualTo(1);
            assertThat(dlqDeliveries.get()).as("3 tentativi sul record DLQ, poi scartato").isEqualTo(3);
        }
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        var h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
