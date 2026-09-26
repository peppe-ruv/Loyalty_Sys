package io.loyaltyhub.hub;

import io.loyaltyhub.common.kafka.LoyaltyHubProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Crea i 5 topic su un broker Kafka/Redpanda reale (docs/13 ADR-023). Fuori dal profilo {@code local}
 * i topic non esistono a priori: qui li dichiara la {@code KafkaAdmin}. Due partizioni come nel resto del modello
 * (docs/05 §1, ADR-004; anche su un broker singolo: partizioni, non repliche), retention 3 giorni. I nomi sono quelli di {@link LoyaltyHubProperties} (ADR-004, i 5 topic fissi).
 * Spento nel profilo {@code inproc} (bus in-process, docs/13 ADR-024): senza broker non c'è nulla da creare.
 */
@Configuration
@Profile("!inproc")
public class HubKafkaTopics {

    @Bean
    public KafkaAdmin.NewTopics hubTopics(LoyaltyHubProperties props) {
        NewTopic[] topics = props.getTopics().all().stream()
                .map(name -> TopicBuilder.name(name)
                        .partitions(2)
                        .replicas(1)
                        .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(3L * 24 * 3600 * 1000))
                        .build())
                .toArray(NewTopic[]::new);
        return new KafkaAdmin.NewTopics(topics);
    }
}
