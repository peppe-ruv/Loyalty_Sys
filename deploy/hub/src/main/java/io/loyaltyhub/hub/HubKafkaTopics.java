package io.loyaltyhub.hub;

import io.loyaltyhub.common.kafka.LoyaltyHubProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Crea i 5 topic sul broker della demo (Redpanda single-node, docs/13 ADR-023). Fuori dal profilo {@code local}
 * i topic non esistono a priori: qui li dichiara la {@code KafkaAdmin}. Una sola partizione (broker singolo),
 * retention 3 giorni (docs/05 §1). I nomi sono quelli di {@link LoyaltyHubProperties} (ADR-004, i 5 topic fissi).
 */
@Configuration
public class HubKafkaTopics {

    @Bean
    public KafkaAdmin.NewTopics hubTopics(LoyaltyHubProperties props) {
        NewTopic[] topics = props.getTopics().all().stream()
                .map(name -> TopicBuilder.name(name)
                        .partitions(1)
                        .replicas(1)
                        .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(3L * 24 * 3600 * 1000))
                        .build())
                .toArray(NewTopic[]::new);
        return new KafkaAdmin.NewTopics(topics);
    }
}
