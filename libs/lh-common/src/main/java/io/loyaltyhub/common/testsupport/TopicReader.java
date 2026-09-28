package io.loyaltyhub.common.testsupport;

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
 * Record pubblicati su un topic, letti da un test d'integrazione (docs/06 §9) senza finestre di tempo. Solo test: sta nel
 * jar principale di lh-common, come {@link EmbeddedKafkaBrokersTestListener}, perché ogni modulo la trovi sul proprio
 * classpath di test (usa solo dipendenze già di compilazione).
 * <p>
 * Ogni lettura procede in tre passi, ciascuno con un tetto ({@link #TIMEOUT}) e un errore che riporta lo stato osservato:
 * <ol>
 *   <li>attende che il servizio abbia elaborato gli eventi {@code consumedFirst} che il test ha pubblicato su Kafka
 *       ({@code processed_event}, scritto nella stessa transazione della logica e dell'outbox, docs/06 §1);</li>
 *   <li>attende che il relay abbia pubblicato tutte le righe dell'outbox dirette al topic (marca {@code published_at}
 *       solo dopo l'ack del broker, nella transazione del batch);</li>
 *   <li>legge il topic fino alla fine osservata in quel momento, ignorando i record successivi.</li>
 * </ol>
 * Il risultato contiene esattamente i record scritti fino a quel punto: un doppione è sempre visibile, un record assente
 * è davvero assente. La barriera del passo 1 è un parametro obbligatorio perché senza di essa un controllo di assenza o
 * di "esattamente uno" non prova nulla: il record potrebbe arrivare dopo la lettura. {@code consumedFirst} vuoto è
 * corretto solo per record scritti in una transazione già conclusa dal test (una chiamata HTTP che ha già risposto, un
 * metodo di servizio già tornato) o dopo un'altra barriera ({@link ListenerGroups#awaitCommitted}: per esempio un
 * doppione con lo stesso id, un type ignorato o un record finito in DLQ, che non scrivono {@code processed_event}).
 * <p>
 * Il consumer si assegna da sé le partizioni ({@code assign} + {@code seek}): nessun consumer group, quindi niente
 * FindCoordinator/JoinGroup. Con {@code subscribe} e un {@code group.id} nuovo la prima lettura aspettava il group
 * coordinator del broker embedded, che dopo l'avvio del contesto risponde {@code NOT_COORDINATOR} finché non ha caricato
 * le 50 partizioni di {@code __consumer_offsets} (da 1,5 s a oltre 30 s su una macchina carica): l'attesa consumava la
 * finestra fissa dei vecchi helper, che restituivano una lista vuota anche col record già sul topic.
 */
public final class TopicReader {

    /** Tetto di ogni attesa: elaborazione, relay dell'outbox e lettura del topic, anche su una macchina carica. */
    public static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final Duration POLL = Duration.ofMillis(200);

    private final String bootstrap;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final String topic;

    /**
     * Lettore di {@code topic} sul broker di {@code spring.embedded.kafka.brokers} (letto a ogni lettura); {@code jdbc}
     * è il database del servizio ({@code processed_event}, {@code outbox}).
     */
    public TopicReader(JdbcClient jdbc, ObjectMapper mapper, String topic) {
        this(null, jdbc, mapper, topic);
    }

    /** Come {@link #TopicReader(JdbcClient, ObjectMapper, String)} con un broker esplicito (test senza contesto Spring). */
    public TopicReader(String bootstrap, JdbcClient jdbc, ObjectMapper mapper, String topic) {
        this.bootstrap = bootstrap;
        this.jdbc = Objects.requireNonNull(jdbc);
        this.mapper = Objects.requireNonNull(mapper);
        this.topic = Objects.requireNonNull(topic);
    }

    public String topic() {
        return topic;
    }

    /** Eventi di {@code subject} e {@code type} pubblicati dopo l'elaborazione di {@code consumedFirst}. */
    public List<JsonNode> published(Collection<String> consumedFirst, String subject, String type) {
        return published(consumedFirst,
                e -> subject.equals(e.path("subject").asString()) && type.equals(e.path("type").asString()));
    }

    /**
     * Eventi (valore JSON del record) che soddisfano {@code match}, nell'ordine del topic, dopo che il servizio ha
     * elaborato {@code consumedFirst} (ids degli eventi pubblicati dal test su Kafka; vuoto solo per record di
     * transazioni già concluse).
     */
    public List<JsonNode> published(Collection<String> consumedFirst, Predicate<JsonNode> match) {
        try (Tail tail = tail(false)) {
            return tail.published(consumedFirst, match);
        }
    }

    /** Come {@link #published(Collection, Predicate)} sul record intero (chiave, header, valore non JSON). */
    public List<ConsumerRecord<String, String>> records(Collection<String> consumedFirst,
                                                        Predicate<ConsumerRecord<String, String>> match) {
        try (Tail tail = tail(false)) {
            return tail.records(consumedFirst, match);
        }
    }

    /**
     * Lettore che resta aperto e accumula i record letti, per test che leggono il topic molte volte (ogni lettura
     * riprende da dove era arrivata la precedente). {@code fromEnd}: parte dalla fine del topic osservata a outbox
     * svuotato, così vede solo i record pubblicati da quel momento (lo "svuotamento dell'arretrato" dei vecchi helper,
     * che con {@code subscribe} si fermava al primo poll vuoto, prima che il consumer avesse le partizioni).
     */
    public Tail tail(boolean fromEnd) {
        if (fromEnd) {
            awaitOutboxDrained();
        }
        return new Tail(fromEnd);
    }

    /**
     * Barriera di consumo per gli eventi che il test pubblica su Kafka: attende che il servizio li abbia elaborati tutti.
     * Utile anche da sola prima di verificare lo stato del DB (es. un doppione di {@code effectId} scartato).
     */
    public void awaitConsumed(Collection<String> eventIds) {
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

    /** Lettore aperto su tutte le partizioni del topic, senza consumer group; vedi {@link #tail(boolean)}. */
    public final class Tail implements AutoCloseable {

        private final KafkaConsumer<String, String> consumer;
        private final List<TopicPartition> partitions;
        private final List<ConsumerRecord<String, String>> seen = new ArrayList<>();
        /** Valori JSON dei primi {@code parsed.size()} record di {@link #seen}: ogni record si legge una volta sola. */
        private final List<JsonNode> parsed = new ArrayList<>();

        private Tail(boolean fromEnd) {
            consumer = new KafkaConsumer<>(Map.of(
                    "bootstrap.servers", brokers(bootstrap),
                    "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class));
            try {
                partitions = consumer.partitionsFor(topic).stream()
                        .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
                if (partitions.isEmpty()) {
                    throw new AssertionError("topic " + topic + " senza partizioni sul broker embedded");
                }
                consumer.assign(partitions);
                if (fromEnd) {
                    consumer.endOffsets(partitions).forEach(consumer::seek);
                } else {
                    consumer.seekToBeginning(partitions);
                }
            } catch (RuntimeException | Error e) {
                consumer.close();
                throw e;
            }
        }

        /** Eventi letti finora (dall'apertura) che soddisfano {@code match}, dopo le barriere di {@link TopicReader}. */
        public List<JsonNode> published(Collection<String> consumedFirst, Predicate<JsonNode> match) {
            records(consumedFirst, r -> false);
            for (int i = parsed.size(); i < seen.size(); i++) {
                parsed.add(mapper.readTree(seen.get(i).value()));
            }
            return parsed.stream().filter(match).toList();
        }

        /** Record letti finora (dall'apertura) che soddisfano {@code match}, dopo le barriere di {@link TopicReader}. */
        public List<ConsumerRecord<String, String>> records(Collection<String> consumedFirst,
                                                            Predicate<ConsumerRecord<String, String>> match) {
            awaitConsumed(Objects.requireNonNull(consumedFirst, "consumedFirst"));
            awaitOutboxDrained();
            readToEnd();
            return seen.stream().filter(match).toList();
        }

        /** Legge fino alla fine osservata ora; i record oltre quella fine restano per la lettura successiva. */
        private void readToEnd() {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            while (!reached(end)) {
                if (System.nanoTime() > deadline) {
                    Map<TopicPartition, String> state = new LinkedHashMap<>();
                    end.forEach((tp, offset) -> state.put(tp, "posizione " + consumer.position(tp) + ", fine " + offset));
                    throw new AssertionError("lettura di " + topic + " incompleta dopo " + TIMEOUT.toSeconds() + " s ("
                            + seen.size() + " record letti) " + state);
                }
                for (ConsumerRecord<String, String> r : consumer.poll(POLL)) {
                    TopicPartition tp = new TopicPartition(r.topic(), r.partition());
                    if (r.offset() >= end.get(tp)) {
                        // oltre la fine osservata: si rilegge alla prossima lettura, nell'ordine della partizione
                        consumer.seek(tp, Math.min(consumer.position(tp), r.offset()));
                        continue;
                    }
                    seen.add(r);
                }
            }
        }

        private boolean reached(Map<TopicPartition, Long> end) {
            for (Map.Entry<TopicPartition, Long> e : end.entrySet()) {
                if (consumer.position(e.getKey()) < e.getValue()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void close() {
            consumer.close();
        }
    }

    /** Broker esplicito oppure quello del contesto di test in uso ({@code spring.embedded.kafka.brokers}). */
    static String brokers(String bootstrap) {
        String brokers = bootstrap != null ? bootstrap : System.getProperty("spring.embedded.kafka.brokers");
        if (brokers == null || brokers.isBlank()) {
            throw new AssertionError("broker Kafka del test non noto: spring.embedded.kafka.brokers non impostata");
        }
        return brokers;
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
