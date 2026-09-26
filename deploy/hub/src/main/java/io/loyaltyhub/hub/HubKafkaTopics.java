package io.loyaltyhub.hub;

import io.loyaltyhub.common.kafka.LhTopicPartitionGuard;
import io.loyaltyhub.common.kafka.LhTopics;
import io.loyaltyhub.common.kafka.LoyaltyHubProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Crea i 5 topic su un broker Kafka/Redpanda reale (docs/13 ADR-023). Fuori dal profilo {@code local}
 * i topic non esistono a priori: qui li dichiara la {@code KafkaAdmin}. I nomi sono quelli di
 * {@link LoyaltyHubProperties} (ADR-004, i 5 topic fissi); la forma viene da {@code loyaltyhub.topic-settings.*}
 * (F2-EVT-04, ADR-028): per default due partizioni come nel resto del modello (docs/05 §1; anche su un broker
 * singolo: partizioni, non repliche), una replica, retention 3 giorni. Con {@code loyaltyhub.topic-settings.create=false}
 * (chart Helm con Strimzi) i topic li possiede l'operatore e l'hub non li dichiara. Un aumento di partizioni su topic
 * esistenti è rifiutato salvo {@code allow-partition-increase} ({@link LhTopicPartitionGuard}).
 * Spento nel profilo {@code inproc} (bus in-process, docs/13 ADR-024): senza broker non c'è nulla da creare.
 */
@Configuration
@Profile("!inproc")
@ConditionalOnProperty(prefix = "loyaltyhub.topic-settings", name = "create", havingValue = "true", matchIfMissing = true)
public class HubKafkaTopics {

    @Bean
    public KafkaAdmin.NewTopics hubTopics(LoyaltyHubProperties props) {
        return new KafkaAdmin.NewTopics(LhTopics.newTopics(props));
    }

    @Bean
    @Lazy(false)
    public LhTopicPartitionGuard hubTopicPartitionGuard(LoyaltyHubProperties props, KafkaAdmin kafkaAdmin) {
        return new LhTopicPartitionGuard(props, kafkaAdmin);
    }
}
