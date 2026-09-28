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
import java.util.function.Predicate;

/**
 * Fatti pubblicati su {@code lh.facts.v1} letti da un test d'integrazione (docs/06 §9), senza finestre di tempo.
 * <p>
 * {@link #published} attende prima che il relay abbia pubblicato tutte le righe dell'outbox dirette al topic (il relay
 * marca {@code published_at} solo dopo l'ack del broker e nella stessa transazione del batch), poi legge il topic
 * dall'inizio fino alla fine osservata in quel momento. Così il risultato contiene esattamente i fatti scritti
 * dall'applicazione fino a quel punto: un doppione è sempre visibile, un fatto assente è davvero assente.
 * <p>
 * Il consumer si assegna da sé le partizioni ({@code assign} + {@code seekToBeginning}): nessun consumer group, quindi
 * niente FindCoordinator/JoinGroup. Con {@code subscribe} e un {@code group.id} nuovo la prima lettura aspettava il
 * group coordinator del broker embedded, che dopo l'avvio del contesto risponde {@code NOT_COORDINATOR} finché non ha
 * caricato le 50 partizioni di {@code __consumer_offsets} (da 1,5 s a oltre 30 s su una macchina carica): l'attesa
 * consumava la finestra fissa dei vecchi helper, che restituivano una lista vuota anche col fatto già sul topic.
 * <p>
 * Ogni attesa ha un tetto ({@link #TIMEOUT}) e allo scadere fallisce con lo stato osservato (righe non pubblicate,
 * posizione e fine delle partizioni).
 */
final class FactsTopic {

    static final String TOPIC = "lh.facts.v1";
    /** Tetto di ogni attesa: relay dell'outbox e lettura del topic, anche su una macchina carica. */
    static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final Duration POLL = Duration.ofMillis(200);

    private FactsTopic() {
    }

    /** Fatti di {@code subject} e {@code type} pubblicati finora su {@link #TOPIC}. */
    static List<JsonNode> published(JdbcClient jdbc, ObjectMapper mapper, String subject, String type) {
        return published(jdbc, mapper,
                e -> subject.equals(e.path("subject").asString()) && type.equals(e.path("type").asString()));
    }

    /** Fatti che soddisfano {@code match} pubblicati finora su {@link #TOPIC}, nell'ordine del topic. */
    static List<JsonNode> published(JdbcClient jdbc, ObjectMapper mapper, Predicate<JsonNode> match) {
        awaitOutboxDrained(jdbc);
        List<JsonNode> out = new ArrayList<>();
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class))) {
            List<TopicPartition> partitions = consumer.partitionsFor(TOPIC).stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
            if (partitions.isEmpty()) {
                throw new AssertionError("topic " + TOPIC + " senza partizioni sul broker embedded");
            }
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            long scanned = 0;
            while (!reached(consumer, end)) {
                if (System.nanoTime() > deadline) {
                    Map<TopicPartition, String> state = new LinkedHashMap<>();
                    end.forEach((tp, offset) -> state.put(tp, "posizione " + consumer.position(tp) + ", fine " + offset));
                    throw new AssertionError("lettura di " + TOPIC + " incompleta dopo " + TIMEOUT.toSeconds() + " s ("
                            + scanned + " record letti) " + state + "; trovati finora: " + out);
                }
                for (ConsumerRecord<String, String> r : consumer.poll(POLL)) {
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
     * {@code processed_event} è scritto nella stessa transazione della logica e dell'outbox (docs/06 §1), quindi dopo
     * questa attesa i fatti che ne derivano sono già nell'outbox e {@link #published} li trova, doppioni compresi.
     */
    static void awaitConsumed(JdbcClient jdbc, Collection<String> eventIds) {
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

    /** Attende che nessuna riga dell'outbox diretta a {@link #TOPIC} sia ancora da pubblicare. */
    static void awaitOutboxDrained(JdbcClient jdbc) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        long pending;
        while ((pending = jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL AND topic = ?")
                .param(TOPIC).query(Long.class).single()) > 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("outbox: " + pending + " righe per " + TOPIC + " ancora da pubblicare dopo "
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
