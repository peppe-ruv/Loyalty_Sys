package io.loyaltyhub.testsupport;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.GroupState;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RetriableException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Attese deterministiche sui consumer group dei listener Kafka di un servizio, per i test d'integrazione (docs/06 §9).
 * Solo test: modulo {@code lh-test-support}, dichiarato con scope {@code test} da lh-common e dai servizi, quindi fuori
 * dai jar di produzione.
 * <ul>
 *   <li>{@link #awaitStable} — da chiamare prima di pubblicare gli eventi che il test si aspetta elaborati: ogni consumer
 *       di ogni container è membro del suo gruppo, il gruppo è {@code STABLE} per il coordinator, ogni partizione dei
 *       topic ascoltati ha un consumer e ogni container ha già applicato l'assegnazione che il coordinator gli ha dato.</li>
 *   <li>{@link #awaitCommitted} — barriera di elaborazione per i record che il test ha pubblicato: il gruppo ha confermato
 *       l'offset oltre ciascuno di essi. Con l'ack {@code MANUAL_IMMEDIATE} di lh-common l'offset si conferma dopo il
 *       commit della transazione del listener, anche per un record ignorato (type non gestito, doppione) e dopo la
 *       pubblicazione in DLQ di un record fallito ({@code commitRecovered}): vale anche dove {@code processed_event} non
 *       viene scritto.</li>
 * </ul>
 * Su un broker embedded appena avviato un gruppo si forma in più giri: i consumer ricevono {@code NOT_COORDINATOR} finché
 * {@code __consumer_offsets} non è caricato, il primo ribilanciamento assegna le partizioni ai membri già entrati e i
 * ritardatari, che lo scoprono al heartbeat successivo (3 s), ne aprono un altro. Per questo
 * {@code ContainerTestUtils.waitForAssignment} non basta: con una partizione per topic si sblocca appena il primo consumer
 * del container la riceve, prima del ribilanciamento finale. Il consumer che resta senza partizione è indistinguibile,
 * lato container, da uno non ancora entrato: lo stato del gruppo si chiede al coordinator.
 */
public final class ListenerGroups {

    /** Tetto di ogni attesa, anche su una macchina carica. */
    public static final Duration TIMEOUT = Duration.ofSeconds(30);

    private ListenerGroups() {
    }

    /**
     * Ritorna quando tutti i gruppi dei listener registrati sono stabili e applicati; altrimenti fallisce. Broker da
     * {@code spring.embedded.kafka.brokers}.
     */
    public static void awaitStable(KafkaListenerEndpointRegistry registry) {
        awaitStable(null, registry.getListenerContainers());
    }

    /**
     * Come {@link #awaitStable(KafkaListenerEndpointRegistry)} per container creati a mano. {@code bootstrap} nullo:
     * {@code spring.embedded.kafka.brokers}.
     */
    public static void awaitStable(String bootstrap, Collection<? extends MessageListenerContainer> containers) {
        if (containers.isEmpty()) {
            throw new AssertionError("nessun listener Kafka registrato");
        }
        Map<String, Set<String>> topicsByGroup = topicsByGroup(containers);
        try (Admin admin = admin(bootstrap)) {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            String reason;
            while ((reason = notReady(admin, containers, topicsByGroup, deadline)) != null) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("listener Kafka non pronti dopo " + TIMEOUT.toSeconds() + " s: " + reason);
                }
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("attesa interrotta", e);
        } catch (ExecutionException e) {
            throw new AssertionError("stato dei gruppi non leggibile dal broker embedded", e);
        }
    }

    /**
     * Barriera di elaborazione: ritorna quando ogni gruppo dei listener registrati che ascolta il topic di un record di
     * {@code published} ha confermato l'offset oltre quel record; altrimenti fallisce con gli offset osservati. Un record
     * su un topic che nessun listener ascolta è un errore del test.
     */
    public static void awaitCommitted(KafkaListenerEndpointRegistry registry, Collection<RecordMetadata> published) {
        Map<String, Set<String>> topicsByGroup = topicsByGroup(registry.getListenerContainers());
        Map<String, List<RecordMetadata>> byGroup = new TreeMap<>();
        for (RecordMetadata record : published) {
            boolean listened = false;
            for (Map.Entry<String, Set<String>> e : topicsByGroup.entrySet()) {
                if (e.getValue().contains(record.topic())) {
                    byGroup.computeIfAbsent(e.getKey(), g -> new ArrayList<>()).add(record);
                    listened = true;
                }
            }
            if (!listened) {
                throw new AssertionError("nessun listener del servizio ascolta " + record.topic() + " " + topicsByGroup);
            }
        }
        byGroup.forEach((group, records) -> awaitCommitted(null, group, records));
    }

    /**
     * Barriera di elaborazione per un gruppo dato (container creati a mano): ritorna quando {@code group} ha confermato
     * l'offset oltre ogni record di {@code published}. {@code bootstrap} nullo: {@code spring.embedded.kafka.brokers}.
     */
    public static void awaitCommitted(String bootstrap, String group, Collection<RecordMetadata> published) {
        Objects.requireNonNull(group, "group");
        Map<TopicPartition, Long> required = new LinkedHashMap<>();
        for (RecordMetadata record : published) {
            if (!record.hasOffset()) {
                throw new AssertionError("record senza offset (invio non confermato dal broker): " + record);
            }
            required.merge(new TopicPartition(record.topic(), record.partition()), record.offset() + 1, Math::max);
        }
        if (required.isEmpty()) {
            return;
        }
        try (Admin admin = admin(bootstrap)) {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            Map<TopicPartition, Long> seen = new LinkedHashMap<>();
            while (true) {
                try {
                    Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(group)
                            .partitionsToOffsetAndMetadata().get(remainingMillis(deadline), TimeUnit.MILLISECONDS);
                    seen.clear();
                    boolean done = true;
                    for (Map.Entry<TopicPartition, Long> e : required.entrySet()) {
                        OffsetAndMetadata c = committed.get(e.getKey());
                        seen.put(e.getKey(), c == null ? null : c.offset());
                        if (c == null || c.offset() < e.getValue()) {
                            done = false;
                        }
                    }
                    if (done) {
                        return;
                    }
                } catch (TimeoutException e) {
                    // il broker non ha risposto entro il tetto: il controllo sotto chiude l'attesa
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof RetriableException)) {
                        throw e;
                    }
                    // coordinator in caricamento o metadati non ancora propagati: stato transitorio, si riprova
                }
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("gruppo " + group + ": offset non confermati dopo " + TIMEOUT.toSeconds()
                            + " s; richiesti " + required + ", confermati " + seen);
                }
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("attesa interrotta", e);
        } catch (ExecutionException e) {
            throw new AssertionError("offset del gruppo " + group + " non leggibili dal broker embedded", e);
        }
    }

    /**
     * Barriera di quiete per catene di eventi tra più servizi (es. l'hub: membro → campagna → wallet), dove gli id degli
     * eventi intermedi non sono noti al test: ritorna quando, nello stesso giro, (1) ogni gruppo dei listener registrati ha
     * confermato l'offset fino alla fine di ogni partizione dei suoi topic, (2) nell'outbox non resta nessuna riga da
     * pubblicare e (3) la fine dei topic non si è mossa nel frattempo. Un record in elaborazione non è ancora confermato
     * (condizione 1); i suoi effetti sono nell'outbox prima della conferma (stessa transazione del listener, ack dopo il
     * commit), quindi se l'outbox è vuoto sono già sui topic e avrebbero spostato la fine (condizione 3). Broker da
     * {@code spring.embedded.kafka.brokers}; {@code jdbc} vede l'outbox del servizio (o dell'hub).
     */
    public static void awaitQuiescent(KafkaListenerEndpointRegistry registry, JdbcClient jdbc) {
        Map<String, Set<String>> topicsByGroup = topicsByGroup(registry.getListenerContainers());
        Set<String> allTopics = new TreeSet<>();
        topicsByGroup.values().forEach(allTopics::addAll);
        try (Admin admin = admin(null)) {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            String reason;
            while ((reason = notQuiescent(admin, jdbc, topicsByGroup, allTopics, deadline)) != null) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("sistema non quieto dopo " + TIMEOUT.toSeconds() + " s: " + reason);
                }
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("attesa interrotta", e);
        } catch (ExecutionException e) {
            throw new AssertionError("offset non leggibili dal broker embedded", e);
        }
    }

    /** {@code null} se quieto, altrimenti il motivo osservato. */
    private static String notQuiescent(Admin admin, JdbcClient jdbc, Map<String, Set<String>> topicsByGroup,
                                       Set<String> allTopics, long deadline)
            throws ExecutionException, InterruptedException {
        try {
            Map<TopicPartition, Long> end = endOffsets(admin, allTopics, deadline);
            for (Map.Entry<String, Set<String>> entry : topicsByGroup.entrySet()) {
                Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(entry.getKey())
                        .partitionsToOffsetAndMetadata().get(remainingMillis(deadline), TimeUnit.MILLISECONDS);
                for (Map.Entry<TopicPartition, Long> e : end.entrySet()) {
                    if (!entry.getValue().contains(e.getKey().topic()) || e.getValue() == 0) {
                        continue;
                    }
                    OffsetAndMetadata c = committed.get(e.getKey());
                    if (c == null || c.offset() < e.getValue()) {
                        return entry.getKey() + " su " + e.getKey() + ": confermato " + (c == null ? "nulla" : c.offset())
                                + ", fine " + e.getValue();
                    }
                }
            }
            long pending = jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL").query(Long.class).single();
            if (pending > 0) {
                return "outbox: " + pending + " righe da pubblicare";
            }
            Map<TopicPartition, Long> after = endOffsets(admin, allTopics, deadline);
            return after.equals(end) ? null : "nuovi record pubblicati durante il controllo";
        } catch (TimeoutException e) {
            return "il broker non ha risposto entro il tetto";
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RetriableException retriable) {
                return "il broker non è ancora pronto: " + retriable;
            }
            throw e;
        }
    }

    private static Map<TopicPartition, Long> endOffsets(Admin admin, Set<String> topics, long deadline)
            throws ExecutionException, InterruptedException, TimeoutException {
        Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
        for (TopicDescription d : admin.describeTopics(topics).allTopicNames()
                .get(remainingMillis(deadline), TimeUnit.MILLISECONDS).values()) {
            d.partitions().forEach(p -> latest.put(new TopicPartition(d.name(), p.partition()), OffsetSpec.latest()));
        }
        Map<TopicPartition, Long> end = new TreeMap<>(java.util.Comparator.comparing(TopicPartition::toString));
        admin.listOffsets(latest).all().get(remainingMillis(deadline), TimeUnit.MILLISECONDS)
                .forEach((tp, info) -> end.put(tp, info.offset()));
        return end;
    }

    private static Map<String, Set<String>> topicsByGroup(Collection<? extends MessageListenerContainer> containers) {
        Map<String, Set<String>> topicsByGroup = new TreeMap<>();
        for (MessageListenerContainer c : containers) {
            String[] topics = c.getContainerProperties().getTopics();
            if (topics == null || topics.length == 0) {
                throw new AssertionError("listener " + c.getListenerId() + " senza topic espliciti");
            }
            topicsByGroup.computeIfAbsent(c.getGroupId(), g -> new TreeSet<>()).addAll(List.of(topics));
        }
        return topicsByGroup;
    }

    /**
     * {@code null} se pronti, altrimenti il motivo osservato. Ogni chiamata all'{@link Admin} aspetta al più il tempo
     * che resta fino a {@code deadline}: un giro non può sforare il tetto complessivo.
     */
    private static String notReady(Admin admin, Collection<? extends MessageListenerContainer> containers,
                                   Map<String, Set<String>> topicsByGroup, long deadline)
            throws ExecutionException, InterruptedException {
        // Lato container: per gruppo, clientId del consumer → partizioni già applicate.
        Map<String, Map<String, Set<TopicPartition>>> applied = new TreeMap<>();
        for (MessageListenerContainer c : containers) {
            List<MessageListenerContainer> children = new ArrayList<>();
            if (c instanceof ConcurrentMessageListenerContainer<?, ?> concurrent) {
                children.addAll(concurrent.getContainers());
            } else {
                children.add(c);
            }
            if (children.isEmpty()) {
                return c.getListenerId() + ": nessun consumer avviato";
            }
            for (MessageListenerContainer child : children) {
                Map<String, Collection<TopicPartition>> byClient = child.getAssignmentsByClientId();
                if (byClient == null || byClient.isEmpty()) {
                    return c.getListenerId() + ": consumer non ancora creato";
                }
                for (Map.Entry<String, Collection<TopicPartition>> e : byClient.entrySet()) {
                    Set<TopicPartition> tps = e.getValue() == null ? Set.of() : new HashSet<>(e.getValue());
                    if (applied.computeIfAbsent(c.getGroupId(), g -> new TreeMap<>()).put(e.getKey(), tps) != null) {
                        throw new AssertionError("clientId " + e.getKey() + " usato da più consumer del gruppo "
                                + c.getGroupId() + ": lo stato del gruppo non si può verificare per consumer");
                    }
                }
            }
        }
        Set<String> allTopics = new TreeSet<>();
        topicsByGroup.values().forEach(allTopics::addAll);
        Map<String, TopicDescription> topics;
        Map<String, ConsumerGroupDescription> groups;
        try {
            topics = admin.describeTopics(allTopics).allTopicNames().get(remainingMillis(deadline), TimeUnit.MILLISECONDS);
            groups = admin.describeConsumerGroups(topicsByGroup.keySet()).all()
                    .get(remainingMillis(deadline), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return "il broker non ha descritto topic e gruppi entro il tetto";
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RetriableException retriable) {
                // Topic appena creato non ancora nei metadati, coordinator in caricamento: stato transitorio, si riprova.
                return "il broker non è ancora pronto: " + retriable;
            }
            throw e;
        }
        for (Map.Entry<String, Set<String>> entry : topicsByGroup.entrySet()) {
            String group = entry.getKey();
            ConsumerGroupDescription description = groups.get(group);
            if (description == null || description.groupState() != GroupState.STABLE) {
                return group + " è " + (description == null ? "assente" : description.groupState());
            }
            Map<String, Set<TopicPartition>> byCoordinator = new HashMap<>();
            for (MemberDescription m : description.members()) {
                if (byCoordinator.put(m.clientId(), m.assignment().topicPartitions()) != null) {
                    throw new AssertionError("il coordinator riporta più membri del gruppo " + group + " con clientId "
                            + m.clientId() + ": lo stato del gruppo non si può verificare per consumer");
                }
            }
            Set<TopicPartition> covered = new HashSet<>();
            for (Map.Entry<String, Set<TopicPartition>> consumer : applied.get(group).entrySet()) {
                Set<TopicPartition> assigned = byCoordinator.get(consumer.getKey());
                if (assigned == null) {
                    return group + ": " + consumer.getKey() + " non ancora membro " + byCoordinator.keySet();
                }
                if (!assigned.equals(consumer.getValue())) {
                    return group + ": " + consumer.getKey() + " ha applicato " + consumer.getValue()
                            + ", il coordinator gli ha assegnato " + assigned;
                }
                covered.addAll(assigned);
            }
            for (String topic : entry.getValue()) {
                for (var p : topics.get(topic).partitions()) {
                    TopicPartition tp = new TopicPartition(topic, p.partition());
                    if (!covered.contains(tp)) {
                        return group + ": " + tp + " senza consumer";
                    }
                }
            }
        }
        return null;
    }

    private static Admin admin(String bootstrap) {
        return Admin.create(Map.of("bootstrap.servers", TopicReader.brokers(bootstrap)));
    }

    /** Millisecondi rimasti fino a {@code deadline} ({@link System#nanoTime()}), almeno 1. */
    private static long remainingMillis(long deadline) {
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
    }
}
