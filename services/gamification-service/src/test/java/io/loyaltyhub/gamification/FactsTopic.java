package io.loyaltyhub.gamification;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Fatti pubblicati sul topic dei fatti ({@code loyaltyhub.topics.facts}, di norma {@code lh.facts.v1}) letti da un test
 * d'integrazione (docs/06 §9), senza finestre di tempo.
 * <p>
 * {@link #published} procede in tre passi, ciascuno con un tetto ({@link #TIMEOUT}) e un errore che riporta lo stato
 * osservato:
 * <ol>
 *   <li>attende che il servizio abbia elaborato gli eventi {@code consumedFirst} che il test ha pubblicato su Kafka
 *       ({@code processed_event}, scritto nella stessa transazione della logica e dell'outbox, docs/06 §1);</li>
 *   <li>attende che il relay abbia pubblicato tutte le righe dell'outbox dirette al topic (marca {@code published_at}
 *       solo dopo l'ack del broker, nella transazione del batch);</li>
 *   <li>legge il topic dall'inizio fino alla fine osservata in quel momento, ignorando i record successivi.</li>
 * </ol>
 * Il risultato contiene esattamente i fatti scritti fino a quel punto: un doppione è sempre visibile, un fatto assente è
 * davvero assente. La barriera del passo 1 è un parametro obbligatorio perché senza di essa un controllo di assenza o di
 * "esattamente uno" non prova nulla: il fatto potrebbe arrivare dopo la lettura. {@code consumedFirst} vuoto è corretto
 * solo per fatti scritti in una transazione già conclusa dal test (una chiamata HTTP che ha già risposto).
 * <p>
 * Il consumer si assegna da sé le partizioni ({@code assign} + {@code seekToBeginning}): nessun consumer group, quindi
 * niente FindCoordinator/JoinGroup. Con {@code subscribe} e un {@code group.id} nuovo la prima lettura aspettava il
 * group coordinator del broker embedded, che dopo l'avvio del contesto risponde {@code NOT_COORDINATOR} finché non ha
 * caricato le 50 partizioni di {@code __consumer_offsets} (da 1,5 s a oltre 30 s su una macchina carica): l'attesa
 * consumava la finestra fissa dei vecchi helper, che restituivano una lista vuota anche col fatto già sul topic.
 */
final class FactsTopic {

    /** Tetto di ogni attesa: elaborazione, relay dell'outbox e lettura del topic, anche su una macchina carica. */
    static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final Duration POLL = Duration.ofMillis(200);

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final String topic;

    /** {@code topic}: il valore di {@code loyaltyhub.topics.facts} del contesto di test. */
    FactsTopic(JdbcClient jdbc, ObjectMapper mapper, String topic) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.mapper = Objects.requireNonNull(mapper);
        this.topic = Objects.requireNonNull(topic);
    }

    /** Fatti di {@code subject} e {@code type} pubblicati dopo l'elaborazione di {@code consumedFirst}. */
    List<JsonNode> published(Collection<String> consumedFirst, String subject, String type) {
        return published(consumedFirst,
                e -> subject.equals(e.path("subject").asString()) && type.equals(e.path("type").asString()));
    }

    /**
     * Fatti che soddisfano {@code match}, nell'ordine del topic, dopo che il servizio ha elaborato {@code consumedFirst}
     * (ids degli eventi pubblicati dal test su Kafka; vuoto solo per fatti di transazioni HTTP già concluse).
     */
    List<JsonNode> published(Collection<String> consumedFirst, Predicate<JsonNode> match) {
        awaitConsumed(Objects.requireNonNull(consumedFirst, "consumedFirst"));
        awaitOutboxDrained();
        List<JsonNode> out = new ArrayList<>();
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class))) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
            if (partitions.isEmpty()) {
                throw new AssertionError("topic " + topic + " senza partizioni sul broker embedded");
            }
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            long scanned = 0;
            while (!reached(consumer, end)) {
                if (System.nanoTime() > deadline) {
                    Map<TopicPartition, String> state = new LinkedHashMap<>();
                    end.forEach((tp, offset) -> state.put(tp, "posizione " + consumer.position(tp) + ", fine " + offset));
                    throw new AssertionError("lettura di " + topic + " incompleta dopo " + TIMEOUT.toSeconds() + " s ("
                            + scanned + " record letti) " + state + "; trovati finora: " + out);
                }
                for (ConsumerRecord<String, String> r : consumer.poll(POLL)) {
                    if (r.offset() >= end.get(new TopicPartition(r.topic(), r.partition()))) {
                        continue; // oltre la fine osservata: non fa parte della lettura
                    }
                    scanned++;
                    JsonNode e = mapper.readTree(r.value());
                    if (match.test(e)) {
                        out.add(e);
                    }
                }
            }
        }
        return out;
    }

    /**
     * Barriera di consumo per gli eventi che il test pubblica su Kafka: attende che il servizio li abbia elaborati tutti.
     * Utile anche da sola prima di verificare lo stato del DB (es. un doppione di {@code effectId} scartato).
     */
    void awaitConsumed(Collection<String> eventIds) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        List<String> missing;
        while (!(missing = eventIds.stream().filter(id -> jdbc.sql("SELECT count(*) FROM processed_event WHERE event_id = ?")
                .param(id).query(Long.class).single() == 0).toList()).isEmpty()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("eventi non ancora elaborati dal servizio dopo " + TIMEOUT.toSeconds() + " s: "
                        + missing + " (su " + eventIds.size() + ")");
            }
            sleep();
        }
    }

    /** Attende che nessuna riga dell'outbox diretta al topic sia ancora da pubblicare. */
    private void awaitOutboxDrained() {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        long pending;
        while ((pending = jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL AND topic = ?")
                .param(topic).query(Long.class).single()) > 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("outbox: " + pending + " righe per " + topic + " ancora da pubblicare dopo "
                        + TIMEOUT.toSeconds() + " s (relay fermo?)");
            }
            sleep();
        }
    }

    private static boolean reached(KafkaConsumer<String, String> consumer, Map<TopicPartition, Long> end) {
        for (Map.Entry<TopicPartition, Long> e : end.entrySet()) {
            if (consumer.position(e.getKey()) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("attesa interrotta", e);
        }
    }
}
