package io.loyaltyhub.hub.bus;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bus a eventi <strong>in-process</strong> per la sola demo ospitata a costo zero (docs/13 ADR-024):
 * nel deployable consolidato i 4 servizi vivono nello stesso JVM, quindi produttori e consumatori degli
 * eventi sono nello stesso processo e non serve un broker Kafka esterno. Sostituisce Kafka <em>solo</em> nel
 * profilo {@code inproc}; in locale e nel resto del modello resta Kafka/Redpanda vero (piena fedeltà).
 *
 * <p>Semantica preservata rispetto a Kafka:
 * <ul>
 *   <li><strong>Fan-out per topic</strong>: ogni sottoscrizione (un {@code @KafkaListener}, cioè un gruppo
 *       consumer per servizio) riceve una copia del messaggio.</li>
 *   <li><strong>Asincronia</strong>: la consegna avviene su un thread dedicato, fuori dalla transazione del
 *       relay dell'outbox — ogni consumatore gira nella propria transazione, come col broker.</li>
 *   <li><strong>Ordine</strong>: un solo thread di consegna → FIFO globale (più forte del per-partizione di
 *       Kafka; sufficiente e semplice per una demo).</li>
 *   <li><strong>Ritentativi</strong>: 3 tentativi con backoff 200 ms come l'error handler di lh-common; poi
 *       il messaggio è scartato con log di errore (la demo non ha un consumatore di DLQ).</li>
 * </ul>
 */
public class HubInProcessBus implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HubInProcessBus.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long BACKOFF_MS = 200L;

    private final Map<String, List<Subscription>> byTopic = new ConcurrentHashMap<>();
    private final AtomicLong offset = new AtomicLong();
    private final ExecutorService delivery =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "lh-inproc-bus");
                t.setDaemon(true);
                return t;
            });

    /** Registra un consumatore su un topic (chiamata a startup, un {@code @KafkaListener} per volta). */
    public void subscribe(String topic, String groupId, Delivery consumer) {
        byTopic.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>())
                .add(new Subscription(groupId, consumer));
        log.info("Bus in-process: {} sottoscrive {}", groupId, topic);
    }

    /** Pubblica una riga dell'outbox: consegna asincrona a ogni consumatore del topic. */
    public void publish(ProducerRecord<String, String> record) {
        List<Subscription> subs = byTopic.get(record.topic());
        if (subs == null || subs.isEmpty()) {
            return; // nessun consumatore (es. lh.audit.v1 / lh.dlq.v1 nella demo): come Kafka senza subscriber
        }
        for (Subscription sub : subs) {
            ConsumerRecord<String, String> consumerRecord =
                    new ConsumerRecord<>(record.topic(), 0, offset.getAndIncrement(), record.key(), record.value());
            delivery.execute(() -> deliver(sub, consumerRecord));
        }
    }

    private void deliver(Subscription sub, ConsumerRecord<String, String> record) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                sub.consumer().accept(record);
                return;
            } catch (Exception e) {
                if (attempt == MAX_ATTEMPTS) {
                    log.error("Bus in-process: consegna a {} su {} fallita dopo {} tentativi (scartato): {}",
                            sub.groupId(), record.topic(), MAX_ATTEMPTS, e.toString());
                    return;
                }
                log.warn("Bus in-process: tentativo {}/{} fallito per {} su {}: {}",
                        attempt, MAX_ATTEMPTS, sub.groupId(), record.topic(), e.toString());
                sleep();
            }
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(BACKOFF_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        delivery.shutdown();
        try {
            if (!delivery.awaitTermination(5, TimeUnit.SECONDS)) {
                delivery.shutdownNow();
            }
        } catch (InterruptedException e) {
            delivery.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Consegna di un record a un consumatore; può sollevare (checked) come un {@code @KafkaListener}. */
    @FunctionalInterface
    public interface Delivery {
        void accept(ConsumerRecord<String, String> record) throws Exception;
    }

    private record Subscription(String groupId, Delivery consumer) {
    }
}
