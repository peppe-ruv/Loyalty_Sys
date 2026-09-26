package io.loyaltyhub.hub;

import io.loyaltyhub.common.kafka.LhTopicPartitionGuard;
import io.loyaltyhub.common.kafka.LoyaltyHubProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F2-EVT-04: l'hub dichiara i topic (e la guardia sulle partizioni) solo se {@code create=true}; con Strimzi il chart
 * imposta {@code LH_KAFKA_TOPICS_CREATE=false} e i topic li possiede l'operatore. Spento anche nel profilo {@code inproc}.
 */
class HubKafkaTopicsTest {

    // Nessun broker: allow-partition-increase evita la lettura delle partizioni e l'admin non crea topic all'avvio.
    // Il comportamento della guardia su un broker vero è in TopicEvolutionIT di lh-common.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Support.class, HubKafkaTopics.class)
            .withBean(KafkaAdmin.class, HubKafkaTopicsTest::offlineAdmin)
            .withPropertyValues("loyaltyhub.topic-settings.allow-partition-increase=true");

    private static KafkaAdmin offlineAdmin() {
        KafkaAdmin admin = new KafkaAdmin(Map.of("bootstrap.servers", "localhost:1"));
        admin.setAutoCreate(false);
        return admin;
    }

    @Test
    void createFalseSkipsTopicsAndGuard() {
        runner.withPropertyValues("loyaltyhub.topic-settings.create=false").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(HubKafkaTopics.class);
            assertThat(ctx).doesNotHaveBean(KafkaAdmin.NewTopics.class);
            assertThat(ctx).doesNotHaveBean(LhTopicPartitionGuard.class);
        });
    }

    @Test
    void defaultDeclaresTopicsAndGuard() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(KafkaAdmin.NewTopics.class);
            assertThat(ctx).hasSingleBean(LhTopicPartitionGuard.class);
        });
    }

    @Test
    void inprocProfileSkipsTopics() {
        runner.withInitializer(c -> c.getEnvironment().setActiveProfiles("inproc")).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(KafkaAdmin.NewTopics.class);
        });
    }

    /**
     * Niente {@code @Configuration}: la scansione dei componenti dei {@code @SpringBootTest} dell'hub prenderebbe anche
     * le classi di test e un {@code KafkaAdmin} finto sostituirebbe quello vero negli altri test d'integrazione.
     */
    @EnableConfigurationProperties(LoyaltyHubProperties.class)
    static class Support {
    }
}
