package io.loyaltyhub.gamification;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.GroupState;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;

/**
 * Attesa deterministica che i listener Kafka del servizio siano pronti a consumare (docs/06 §9), da chiamare prima di
 * pubblicare gli eventi che il test si aspetta elaborati: ogni consumer di ogni container è membro del suo gruppo, il
 * gruppo è {@code STABLE} per il coordinator, ogni partizione dei topic ascoltati ha un consumer e ogni container ha già
 * applicato l'assegnazione che il coordinator gli ha dato.
 * <p>
 * Su un broker embedded appena avviato il gruppo {@code lh-gamification} (3 container × concorrenza 2) si forma in più
 * giri: i consumer ricevono {@code NOT_COORDINATOR} finché {@code __consumer_offsets} non è caricato, il primo
 * ribilanciamento assegna le partizioni ai membri già entrati e i ritardatari, che lo scoprono al heartbeat successivo
 * (3 s), ne aprono un altro. Per questo {@code ContainerTestUtils.waitForAssignment} (usato da {@code TestbookRwdBase})
 * qui non basta: con una partizione per topic si sblocca appena il primo consumer del container la riceve, prima del
 * ribilanciamento finale. Il consumer che resta senza partizione è indistinguibile, lato container, da uno non ancora
 * entrato: lo stato del gruppo si chiede al coordinator.
 */
final class ListenerGroups {

    static final Duration TIMEOUT = Duration.ofSeconds(30);

    private ListenerGroups() {
    }

    /** Ritorna quando tutti i gruppi dei listener registrati sono stabili e applicati; altrimenti fallisce. */
    static void awaitStable(KafkaListenerEndpointRegistry registry) {
        Collection<MessageListenerContainer> containers = registry.getListenerContainers();
        if (containers.isEmpty()) {
            throw new AssertionError("nessun listener Kafka registrato");
        }
        Map<String, Set<String>> topicsByGroup = new TreeMap<>();
        for (MessageListenerContainer c : containers) {
            String[] topics = c.getContainerProperties().getTopics();
            if (topics == null || topics.length == 0) {
                throw new AssertionError("listener " + c.getListenerId() + " senza topic espliciti");
            }
            topicsByGroup.computeIfAbsent(c.getGroupId(), g -> new TreeSet<>()).addAll(List.of(topics));
        }
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers")))) {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            String reason;
            while ((reason = notReady(admin, containers, topicsByGroup)) != null) {
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

    /** {@code null} se pronti, altrimenti il motivo osservato. */
    private static String notReady(Admin admin, Collection<MessageListenerContainer> containers,
                                   Map<String, Set<String>> topicsByGroup) throws ExecutionException, InterruptedException {
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
                byClient.forEach((client, tps) -> applied.computeIfAbsent(c.getGroupId(), g -> new TreeMap<>())
                        .put(client, tps == null ? Set.of() : new HashSet<>(tps)));
            }
        }
        Set<String> allTopics = new TreeSet<>();
        topicsByGroup.values().forEach(allTopics::addAll);
        Map<String, TopicDescription> topics = admin.describeTopics(allTopics).allTopicNames().get();
        Map<String, ConsumerGroupDescription> groups = admin.describeConsumerGroups(topicsByGroup.keySet()).all().get();
        for (Map.Entry<String, Set<String>> entry : topicsByGroup.entrySet()) {
            String group = entry.getKey();
            ConsumerGroupDescription description = groups.get(group);
            if (description == null || description.groupState() != GroupState.STABLE) {
                return group + " è " + (description == null ? "assente" : description.groupState());
            }
            Map<String, Set<TopicPartition>> byCoordinator = new HashMap<>();
            for (MemberDescription m : description.members()) {
                byCoordinator.put(m.clientId(), m.assignment().topicPartitions());
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
}
