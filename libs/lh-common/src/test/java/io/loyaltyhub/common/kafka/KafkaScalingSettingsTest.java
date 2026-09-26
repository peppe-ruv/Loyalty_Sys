package io.loyaltyhub.common.kafka;

import io.loyaltyhub.common.config.LhEnvironmentAliases;
import io.loyaltyhub.common.metrics.LhMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F2-EVT-04 (ADR-028): partizioni, repliche, retention e concorrenza configurabili, con i default di Fase 1 invariati
 * e i nomi dei 5 topic mai toccati.
 */
class KafkaScalingSettingsTest {

    private static final String THREE_DAYS = String.valueOf(3L * 24 * 3600 * 1000);

    @Test
    void defaultsAreThoseOfPhaseOne() {
        List<NewTopic> topics = List.of(LhTopics.newTopics(new LoyaltyHubProperties()));

        assertThat(topics).extracting(NewTopic::name)
                .containsExactly("lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1");
        assertThat(topics).allSatisfy(t -> {
            assertThat(t.numPartitions()).isEqualTo(2);
            assertThat(t.replicationFactor()).isEqualTo((short) 1);
            assertThat(t.configs()).containsExactly(Map.entry("retention.ms", THREE_DAYS));
        });
        assertThat(new LoyaltyHubProperties().getConsumer().getConcurrency()).isEqualTo(2);
        assertThat(new LoyaltyHubProperties().getTopicSettings().isCreate()).isTrue();
    }

    @Test
    void enterpriseShapeKeepsTheFiveNames() {
        LoyaltyHubProperties props = new LoyaltyHubProperties();
        LoyaltyHubProperties.TopicSettings s = props.getTopicSettings();
        s.setPartitions(12);
        s.setReplicas((short) 3);
        s.setMinInsyncReplicas(2);
        s.setRetentionMs(604_800_000L);
        s.getRetentionMsByTopic().put("facts", 31_536_000_000L);
        s.getRetentionMsByTopic().put("audit", 31_536_000_000L);

        List<NewTopic> topics = List.of(LhTopics.newTopics(props));

        assertThat(topics).hasSize(5).extracting(NewTopic::name).containsExactlyElementsOf(props.getTopics().all());
        assertThat(topics).allSatisfy(t -> {
            assertThat(t.numPartitions()).isEqualTo(12);
            assertThat(t.replicationFactor()).isEqualTo((short) 3);
            assertThat(t.configs()).containsEntry("min.insync.replicas", "2");
        });
        assertThat(topics).extracting(t -> t.configs().get("retention.ms"))
                .containsExactly("604800000", "604800000", "31536000000", "31536000000", "604800000");
    }

    @Test
    void impossibleShapesStopTheStartup() {
        assertThatThrownBy(() -> LhTopics.newTopics(with(s -> s.setPartitions(0))))
                .hasMessageContaining("partitions");
        assertThatThrownBy(() -> LhTopics.newTopics(with(s -> s.setReplicas((short) 0))))
                .hasMessageContaining("replicas");
        assertThatThrownBy(() -> LhTopics.newTopics(with(s -> s.setMinInsyncReplicas(2))))
                .hasMessageContaining("min-insync-replicas");
        assertThatThrownBy(() -> LhTopics.newTopics(with(s -> s.setRetentionMs(0))))
                .hasMessageContaining("retention-ms");
        assertThatThrownBy(() -> LhTopics.newTopics(with(s -> s.getRetentionMsByTopic().put("fact", 1L))))
                .hasMessageContaining("chiavi sconosciute [fact]");
        // -1 = retention illimitata, ammessa da Kafka.
        assertThat(LhTopics.newTopics(with(s -> s.getRetentionMsByTopic().put("audit", -1L)))[3].configs())
                .containsEntry("retention.ms", "-1");
    }

    @Test
    void listenerConcurrencyComesFromConfiguration() {
        LoyaltyHubProperties props = new LoyaltyHubProperties();
        props.setService("wallet");
        assertThat(container(props).getConcurrency()).isEqualTo(2);

        props.getConsumer().setConcurrency(6);
        assertThat(container(props).getConcurrency()).isEqualTo(6);

        props.getConsumer().setConcurrency(0);
        assertThatThrownBy(() -> container(props)).hasMessageContaining("concurrency");
    }

    @Test
    void environmentVariablesMapToTheSettings() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("LH_KAFKA_CONSUMER_CONCURRENCY", "12");
        vars.put("LH_KAFKA_TOPICS_CREATE", "false");
        vars.put("LH_KAFKA_TOPIC_PARTITIONS", "12");
        vars.put("LH_KAFKA_TOPIC_REPLICAS", "3");
        vars.put("LH_KAFKA_TOPIC_MIN_INSYNC_REPLICAS", "2");
        vars.put("LH_KAFKA_TOPIC_RETENTION_MS", "604800000");
        vars.put("LH_KAFKA_FACTS_RETENTION_MS", "31536000000");
        vars.put("LH_KAFKA_AUDIT_RETENTION_MS", "31536000000");
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, vars));
        new LhEnvironmentAliases().postProcessEnvironment(env, new SpringApplication());

        LoyaltyHubProperties props = Binder.get(env).bindOrCreate("loyaltyhub", LoyaltyHubProperties.class);
        LoyaltyHubProperties.TopicSettings s = props.getTopicSettings();

        assertThat(props.getConsumer().getConcurrency()).isEqualTo(12);
        assertThat(s.isCreate()).isFalse();
        assertThat(s.getPartitions()).isEqualTo(12);
        assertThat(s.getReplicas()).isEqualTo((short) 3);
        assertThat(s.getMinInsyncReplicas()).isEqualTo(2);
        assertThat(Arrays.stream(new String[]{"actions", "facts", "audit"}).map(k -> LhTopics.retentionFor(s, k)))
                .containsExactly(604_800_000L, 31_536_000_000L, 31_536_000_000L);
    }

    private static LoyaltyHubProperties with(java.util.function.Consumer<LoyaltyHubProperties.TopicSettings> change) {
        LoyaltyHubProperties props = new LoyaltyHubProperties();
        change.accept(props.getTopicSettings());
        return props;
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentMessageListenerContainer<String, String> container(LoyaltyHubProperties props) {
        LhKafkaConfiguration cfg = new LhKafkaConfiguration(props, "broker.example:9092");
        KafkaTemplate<String, String> template = cfg.kafkaTemplate(cfg.lhProducerFactory());
        var factory = (ConcurrentKafkaListenerContainerFactory<String, String>) cfg.lhKafkaListenerContainerFactory(
                cfg.lhConsumerFactory(), cfg.lhErrorHandler(template, new LhMetrics(new SimpleMeterRegistry())), true);
        return factory.createContainer("lh.facts.v1");
    }
}
