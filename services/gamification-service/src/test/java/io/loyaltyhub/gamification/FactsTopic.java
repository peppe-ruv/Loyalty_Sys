package io.loyaltyhub.gamification;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fatti pubblicati su {@code lh.facts.v1} letti da un test d'integrazione (docs/06 §9) <em>senza consumer group</em>.
 * <p>
 * Il consumer si assegna da sé le partizioni del topic ({@code assign}) e legge dall'inizio ({@code seekToBeginning}):
 * niente FindCoordinator, JoinGroup o SyncGroup. Con {@code subscribe} e un {@code group.id} nuovo, invece, la prima
 * lettura aspetta il group coordinator del broker embedded, che dopo l'avvio del contesto risponde
 * {@code NOT_COORDINATOR} finché non ha caricato le 50 partizioni di {@code __consumer_offsets}: da 1,5 s a oltre 30 s
 * su una macchina carica. Quell'attesa consumava la finestra fissa di 10 s del vecchio helper, che restituiva una lista
 * vuota anche quando il fatto era già sul topic.
 * <p>
 * {@link #await} legge finché non arrivano {@code expected} fatti del soggetto e del tipo indicati (al più
 * {@link #ARRIVAL}), poi continua finché per {@link #QUIET} non ne arrivano altri: un doppione resta visibile al
 * chiamante, che ne verifica il numero esatto. Se allo scadere ne sono arrivati meno, fallisce col numero osservato e con
 * posizione e fine delle partizioni (fatto non ancora pubblicato dal relay o non letto).
 */
final class FactsTopic {

    static final String TOPIC = "lh.facts.v1";
    /** Tetto all'arrivo dei fatti attesi: copre il giro del relay dell'outbox anche su una macchina carica. */
    static final Duration ARRIVAL = Duration.ofSeconds(30);
    /** Silenzio richiesto dopo l'ultimo fatto trovato, per accorgersi di un doppione. */
    static final Duration QUIET = Duration.ofSeconds(2);

    private static final Duration POLL = Duration.ofMillis(200);

    private FactsTopic() {
    }

    /** Fatti di {@code subject} e {@code type} su {@link #TOPIC}; almeno {@code expected}, altrimenti fallisce. */
    static List<JsonNode> await(ObjectMapper mapper, String subject, String type, int expected) {
        List<JsonNode> out = new ArrayList<>();
        long start = System.nanoTime();
        long arrivalDeadline = start + ARRIVAL.toNanos();
        long hardDeadline = arrivalDeadline + QUIET.toNanos();
        long quietUntil = 0;
        long scanned = 0;
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
            while (true) {
                long now = System.nanoTime();
                boolean arrived = out.size() >= expected;
                if (arrived ? now >= quietUntil || now >= hardDeadline : now >= arrivalDeadline) {
                    break;
                }
                for (ConsumerRecord<String, String> r : consumer.poll(POLL)) {
                    scanned++;
                    JsonNode e = mapper.readTree(r.value());
                    if (subject.equals(e.path("subject").asString()) && type.equals(e.path("type").asString())) {
                        out.add(e);
                        quietUntil = System.nanoTime() + QUIET.toNanos();
                    }
                }
            }
            if (out.size() < expected) {
                Map<TopicPartition, String> state = new LinkedHashMap<>();
                Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);
                for (TopicPartition tp : partitions) {
                    state.put(tp, "posizione " + consumer.position(tp) + ", fine " + ends.get(tp));
                }
                throw new AssertionError("attesi " + expected + " fatti " + type + " per " + subject + " su " + TOPIC
                        + " entro " + ARRIVAL.toSeconds() + " s, arrivati " + out.size() + " su " + scanned
                        + " record letti " + state + ": " + out);
            }
        }
        return out;
    }
}
