package io.loyaltyhub.campaign;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.GroupState;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Campaign §5 (Q-169, Q-489): l'attesa dello snapshot per un'azione di un membro non ancora noto non deve bloccare il
 * ribilanciamento del gruppo {@code lh-campaign}, da cui dipende l'arrivo dello snapshot stesso.
 * <p>
 * Riproduce la corsa di TB-PLT-FRP-003 in modo deterministico: il listener dei fatti è fermo (come un consumer che entra
 * tardi nel gruppo all'avvio); l'azione {@code member.registered} del ponte interno arriva al listener delle azioni, che
 * inizia ad attendere lo snapshot; il listener dei fatti riparte ed entra nel gruppo, aprendo un ribilanciamento. Con
 * l'attesa bloccante ({@code Thread.sleep} nel listener) il consumer delle azioni non tornava a {@code poll()} prima della
 * fine dei tentativi, il consumer dei fatti restava senza partizioni fino ad allora e la valutazione registrava
 * {@code NO_MEMBER}: bonus di benvenuto perso. Con il {@code nack} il ribilanciamento si chiude durante l'attesa, il
 * fatto arriva e CMP-WELCOME scatta. Senza Docker: EmbeddedKafka + Zonky.
 */
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberSnapshotRebalanceIT {

    private static final String GROUP = "lh-campaign";
    private static final String ACTIONS = "lh.actions.v1";
    private static final String FACTS = "lh.facts.v1";
    private static final EmbeddedPostgres PG = startPg();

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private JdbcClient jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=campaign");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @BeforeAll
    void factsListenerOutOfTheGroup() {
        awaitGroup(Set.of(ACTIONS, FACTS));
        factsContainer().stop();
        awaitGroup(Set.of(ACTIONS));
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    void welcomeBonusSurvivesARebalanceWhileTheActionWaitsForTheSnapshot() throws Exception {
        String memberId = "MBR-IT-REB-" + System.nanoTime();
        String actionId = "act-reb-" + System.nanoTime();
        String now = Instant.now().toString();

        // Il fatto è già sul topic, ma nessun consumer di lh-campaign lo legge: lo snapshot manca.
        publish(FACTS, memberId, Map.of(
                "specversion", "1.0", "id", "fact-reb-" + System.nanoTime(), "source", "urn:loyaltyhub:service:member",
                "type", "io.loyaltyhub.fact.member.registered", "subject", "member:" + memberId, "time", now,
                "lhcorrelationid", actionId, "lhhop", 0,
                "data", Map.of("memberId", memberId, "status", "ACTIVE", "channel", "PORTAL", "registeredAt", now,
                        "labels", List.of(), "attributes", Map.of())));
        publish(ACTIONS, memberId, Map.of(
                "specversion", "1.0", "id", actionId, "source", "urn:loyaltyhub:source:internal",
                "type", "io.loyaltyhub.action.member.registered", "subject", "member:" + memberId, "time", now,
                "lhcorrelationid", actionId, "lhhop", 1, "data", Map.of("channel", "PORTAL", "referred", false)));
        // Il consumer delle azioni (partizione assegnata, in long poll) riceve l'azione in pochi millisecondi e inizia ad
        // attendere lo snapshot: 0,5 + 1 + 2 s di tentativi. L'ingresso del listener dei fatti cade dentro quell'attesa.
        Thread.sleep(300);
        factsContainer().start();

        long deadline = System.currentTimeMillis() + 20_000;
        Optional<String> outcome = Optional.empty();
        while (outcome.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            outcome = jdbc.sql("SELECT outcome FROM evaluation_log WHERE action_id = ?")
                    .param(actionId).query(String.class).optional();
        }
        assertThat(outcome).as("valutazione dell'azione %s del membro %s", actionId, memberId).contains("MATCHED");
        String results = jdbc.sql("SELECT results::text FROM evaluation_log WHERE action_id = ?")
                .param(actionId).query(String.class).single();
        assertThat(results).contains("CMP-WELCOME");
    }

    private MessageListenerContainer factsContainer() {
        return registry.getListenerContainers().stream()
                .filter(c -> GROUP.equals(c.getGroupId()))
                .filter(c -> Arrays.asList(c.getContainerProperties().getTopics()).contains(FACTS))
                .findFirst().orElseThrow();
    }

    /**
     * Attende che {@code lh-campaign} sia {@code STABLE} per il coordinator, che i suoi membri coprano tutte le
     * partizioni di {@code topics} e nessun'altra, e che ogni container in esecuzione abbia applicato l'assegnazione.
     */
    private void awaitGroup(Set<String> topics) {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers")))) {
            long deadline = System.currentTimeMillis() + 30_000;
            String reason = "mai verificato";
            while (System.currentTimeMillis() < deadline) {
                reason = notReady(admin, topics);
                if (reason == null) {
                    return;
                }
                Thread.sleep(100);
            }
            throw new AssertionError("gruppo " + GROUP + " non pronto dopo 30 s: " + reason);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } catch (Exception e) {
            throw new AssertionError("stato del gruppo non leggibile", e);
        }
    }

    private String notReady(Admin admin, Set<String> topics) throws Exception {
        ConsumerGroupDescription group = admin.describeConsumerGroups(List.of(GROUP)).all()
                .get(5, TimeUnit.SECONDS).get(GROUP);
        if (group == null || group.groupState() != GroupState.STABLE) {
            return GROUP + " è " + (group == null ? "assente" : group.groupState());
        }
        Set<TopicPartition> byCoordinator = new HashSet<>();
        for (MemberDescription m : group.members()) {
            byCoordinator.addAll(m.assignment().topicPartitions());
        }
        Set<TopicPartition> expected = new HashSet<>();
        admin.describeTopics(topics).allTopicNames().get(5, TimeUnit.SECONDS).forEach((name, d) ->
                d.partitions().forEach(p -> expected.add(new TopicPartition(name, p.partition()))));
        if (!byCoordinator.equals(expected)) {
            return "assegnate " + byCoordinator + ", attese " + expected;
        }
        Set<TopicPartition> applied = new HashSet<>();
        for (MessageListenerContainer c : registry.getListenerContainers()) {
            if (GROUP.equals(c.getGroupId()) && c.isRunning() && c.getAssignedPartitions() != null) {
                applied.addAll(c.getAssignedPartitions());
            }
        }
        return applied.equals(expected) ? null : "applicate dai container " + applied + ", attese " + expected;
    }

    private void publish(String topic, String key, Map<String, Object> event) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class))) {
            producer.send(new ProducerRecord<>(topic, key, mapper.writeValueAsString(event))).get();
        }
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
