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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.HashMap;
import java.util.Map;

/**
 * Configurazione Kafka condivisa (docs/06 §5): produttore idempotente, consumatore ad ack manuale,
 * error handler con 3 tentativi → DLQ {@code lh.dlq.v1}, e i 5 topic creati solo col profilo {@code local}.
 * Concorrenza dei listener e forma dei topic da {@link LoyaltyHubProperties} (F2-EVT-04).
 */
@Configuration(proxyBeanMethods = false)
@org.springframework.kafka.annotation.EnableKafka
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

    /**
     * Admin per creare i topic dai bean NewTopic (profilo local, hub su broker reale) con la sicurezza configurata. Con
     * {@code modify-configs} (default: acceso solo nel profilo {@code enterprise}) applica anche ai topic esistenti le
     * configurazioni cambiate, per esempio la retention (F2-EVT-04).
     */
    @Bean
    @ConditionalOnMissingBean
    public KafkaAdmin kafkaAdmin(Environment environment) {
        Map<String, Object> cfg = new HashMap<>(LhKafkaSecurity.properties(props.getKafka()));
        cfg.put(org.apache.kafka.clients.admin.AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        KafkaAdmin admin = new KafkaAdmin(cfg);
        admin.setModifyTopicConfigs(modifyTopicConfigs(props, environment));
        return admin;
    }

    /** {@code modify-configs} esplicito, altrimenti acceso solo nel profilo {@code enterprise}. */
    public static boolean modifyTopicConfigs(LoyaltyHubProperties props, Environment environment) {
        Boolean explicit = props.getTopicSettings().getModifyConfigs();
        return explicit != null ? explicit : environment.matchesProfiles("enterprise");
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

    /**
     * Error handler: 3 tentativi (1 + 2), poi recoverer verso {@code lh.dlq.v1} con gli header {@code lh-*} di docs/04 §5
     * ({@code lh-original-topic}, {@code lh-consumer}, {@code lh-error-class}, {@code lh-error-message},
     * {@code lh-attempts}) più {@code lh-error-code} ({@link DlqRecords}).
     */
    @Bean
    public DefaultErrorHandler lhErrorHandler(KafkaTemplate<String, String> template, LhMetrics metrics) {
        String dlq = props.getTopics().getDlq();
        long[] backoffs = props.getConsumer().getRetryBackoffMs() == null
                ? new long[0] : props.getConsumer().getRetryBackoffMs();
        int maxAttempts = backoffs.length + 1;
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(dlq, -1));
        recoverer.setHeadersFunction((record, ex) -> {
            Throwable cause = DlqRecords.unwrap(ex);
            String group = ex instanceof ListenerExecutionFailedException lefe && lefe.getGroupId() != null
                    ? lefe.getGroupId() : consumerGroup();
            metrics.eventDlq(headerType(record), DlqRecords.errorCode(cause));
            return DlqRecords.headers(record.topic(), group, cause, DlqRecords.attemptsFor(cause, maxAttempts));
        });
        // Ritardi configurabili (default 1 s, 5 s ⇒ 3 tentativi, docs/12 accettazione M0, SPEC-GAP: Q-131);
        // gli eventi non ritentabili vanno subito in DLQ.
        SequenceBackOff retries = new SequenceBackOff(backoffs);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, retries);
        // LOOP_GUARD è deterministico come un errore di validazione (docs/04 §5): nessun ritentativo lo risolverebbe.
        handler.addNotRetryableExceptions(NonRetryableEventException.class, LoopGuardException.class,
                tools.jackson.core.JacksonException.class);
        // Stessa classificazione del bus in-process (DlqRecords): un errore non ritentabile, anche se avvolto da
        // un'altra eccezione, va subito in DLQ.
        handler.setBackOffFunction((record, ex) -> DlqRecords.retryable(DlqRecords.unwrap(ex))
                ? retries : new SequenceBackOff(new long[0]));
        return handler;
    }

    @Bean
    @ConditionalOnMissingBean(name = "lhKafkaListenerContainerFactory")
    public KafkaListenerContainerFactory<?> lhKafkaListenerContainerFactory(
            ConsumerFactory<String, String> cf, DefaultErrorHandler errorHandler,
            @Value("${spring.kafka.listener.auto-startup:true}") boolean autoStartup) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cf);
        // Consumer per listener configurabili (F2-EVT-04, ADR-028): default 2 come le partizioni di Fase 1.
        factory.setConcurrency(LhTopics.concurrency(props));
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // Il fattore custom non eredita spring.kafka.listener.auto-startup (vale solo per quello di Boot):
        // qui lo onoriamo, così nell'hub in profilo inproc (senza broker) i container reali non partono
        // e non martellano localhost:9092 — al loro posto c'è il bus in-process (ADR-024).
        factory.setAutoStartup(autoStartup);
        return factory;
    }

    // --- Topic locali (solo profilo local): per default 5 topic × 2 partizioni, retention 3 giorni (docs/05 §1);
    //     forma configurabile con loyaltyhub.topic-settings.* (F2-EVT-04) ---

    @Configuration(proxyBeanMethods = false)
    @Profile("local")
    static class LocalTopics {

        private final LoyaltyHubProperties props;

        LocalTopics(LoyaltyHubProperties props) {
            this.props = props;
        }

        @Bean
        @ConditionalOnProperty(prefix = "loyaltyhub.topic-settings", name = "create", havingValue = "true",
                matchIfMissing = true)
        KafkaAdmin.NewTopics lhTopics() {
            return new KafkaAdmin.NewTopics(LhTopics.newTopics(props));
        }

        /** Rifiuta un aumento di partizioni non autorizzato prima che KafkaAdmin lo applichi (F2-EVT-04). */
        @Bean
        @org.springframework.context.annotation.Lazy(false)
        @ConditionalOnProperty(prefix = "loyaltyhub.topic-settings", name = "create", havingValue = "true",
                matchIfMissing = true)
        LhTopicPartitionGuard lhTopicPartitionGuard(KafkaAdmin kafkaAdmin) {
            return new LhTopicPartitionGuard(props, kafkaAdmin);
        }
    }

    private String consumerGroup() {
        String s = props.getService();
        return s.startsWith("lh-") ? s : "lh-" + s;
    }

    private static String headerType(org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record) {
        var h = record.headers().lastHeader(LhHeaders.TYPE);
        return h == null ? "unknown" : new String(h.value(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
