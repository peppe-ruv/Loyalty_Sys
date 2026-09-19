package io.loyaltyhub.common.kafka;

import io.loyaltyhub.common.event.LhHeaders;
import io.loyaltyhub.common.metrics.LhMetrics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Configurazione Kafka condivisa (docs/06 §5): produttore idempotente, consumatore ad ack manuale,
 * error handler con 3 tentativi → DLQ {@code lh.dlq.v1}, e i 5 topic creati solo col profilo {@code local}.
 */
@Configuration(proxyBeanMethods = false)
public class LhKafkaConfiguration {

    private final LoyaltyHubProperties props;
    private final String bootstrapServers;

    public LhKafkaConfiguration(LoyaltyHubProperties props,
                                @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
        this.props = props;
        this.bootstrapServers = bootstrapServers;
    }

    @Bean
    @ConditionalOnMissingBean
    public ProducerFactory<String, String> lhProducerFactory() {
        Map<String, Object> cfg = new HashMap<>(LhKafkaSecurity.properties(props.getKafka()));
        cfg.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.ACKS_CONFIG, "all");
        cfg.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(cfg);
    }

    @Bean
    @ConditionalOnMissingBean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> pf) {
        return new KafkaTemplate<>(pf);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConsumerFactory<String, String> lhConsumerFactory() {
        Map<String, Object> cfg = new HashMap<>(LhKafkaSecurity.properties(props.getKafka()));
        cfg.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        cfg.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup());
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        cfg.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        cfg.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        return new DefaultKafkaConsumerFactory<>(cfg);
    }

    /** Error handler: 3 tentativi (1 + 2), poi recoverer verso {@code lh.dlq.v1} con header {@code lh-error-code}. */
    @Bean
    public DefaultErrorHandler lhErrorHandler(KafkaTemplate<String, String> template, LhMetrics metrics) {
        String dlq = props.getTopics().getDlq();
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(dlq, -1));
        recoverer.setHeadersFunction((record, ex) -> {
            String code = errorCode(ex);
            record.headers().add(LhHeaders.ERROR_CODE, code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            metrics.eventDlq(headerType(record), code);
            return record.headers();
        });
        // maxRetries = 2 ⇒ 3 tentativi totali (docs/12 accettazione M0).
        return new DefaultErrorHandler(recoverer, new FixedBackOff(200L, 2));
    }

    @Bean
    @ConditionalOnMissingBean(name = "lhKafkaListenerContainerFactory")
    public KafkaListenerContainerFactory<?> lhKafkaListenerContainerFactory(
            ConsumerFactory<String, String> cf, DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cf);
        factory.setConcurrency(2);
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }

    // --- Topic locali (solo profilo local): 5 topic × 2 partizioni, retention 3 giorni (docs/05 §1) ---

    @Configuration(proxyBeanMethods = false)
    @Profile("local")
    static class LocalTopics {

        private final LoyaltyHubProperties props;

        LocalTopics(LoyaltyHubProperties props) {
            this.props = props;
        }

        @Bean
        KafkaAdmin.NewTopics lhTopics() {
            var topics = props.getTopics().all().stream()
                    .map(name -> TopicBuilder.name(name)
                            .partitions(2)
                            .replicas(1)
                            .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(3L * 24 * 3600 * 1000))
                            .build())
                    .toArray(org.apache.kafka.clients.admin.NewTopic[]::new);
            return new KafkaAdmin.NewTopics(topics);
        }
    }

    private String consumerGroup() {
        String s = props.getService();
        return s.startsWith("lh-") ? s : "lh-" + s;
    }

    private static String errorCode(Exception ex) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        if (cause instanceof LoopGuardException) {
            return "LOOP_GUARD";
        }
        return cause.getClass().getSimpleName();
    }

    private static String headerType(org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record) {
        var h = record.headers().lastHeader(LhHeaders.TYPE);
        return h == null ? "unknown" : new String(h.value(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
