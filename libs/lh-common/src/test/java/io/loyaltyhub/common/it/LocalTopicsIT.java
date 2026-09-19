package io.loyaltyhub.common.it;

import io.loyaltyhub.common.kafka.LhKafkaConfiguration;
import io.loyaltyhub.common.kafka.LoyaltyHubProperties;
import io.loyaltyhub.common.metrics.LhMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica il cuore di M0.4 (docs/11 §9): col profilo {@code local}, lh-common crea esattamente i 5 topic
 * del sistema, ciascuno con 2 partizioni (ADR-004). Senza Docker: broker Kafka in-JVM.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalTopicsIT {

    private EmbeddedKafkaKraftBroker broker;
    private String bootstrap;

    @BeforeAll
    void up() throws Exception {
        // Broker senza topic predefiniti: devono nascere dai bean del profilo local.
        broker = new EmbeddedKafkaKraftBroker(1, 1);
        broker.afterPropertiesSet();
        bootstrap = broker.getBrokersAsString();
    }

    @AfterAll
    void down() {
        if (broker != null) {
            broker.destroy();
        }
    }

    @Test
    void localProfileCreatesFiveTopicsWithTwoPartitions() {
        new ApplicationContextRunner()
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("local"))
                .withPropertyValues("spring.kafka.bootstrap-servers=" + bootstrap)
                .withUserConfiguration(TestConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    LoyaltyHubProperties props = context.getBean(LoyaltyHubProperties.class);
                    List<String> expected = props.getTopics().all();
                    assertThat(expected).hasSize(5);

                    try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap))) {
                        Map<String, TopicDescription> described =
                                admin.describeTopics(expected).allTopicNames().get();
                        assertThat(described.keySet()).containsExactlyInAnyOrderElementsOf(expected);
                        described.forEach((name, desc) ->
                                assertThat(desc.partitions()).as("2 partizioni per " + name).hasSize(2));
                    }
                });
    }

    @Configuration(proxyBeanMethods = false)
    @org.springframework.context.annotation.Import(LhKafkaConfiguration.class)
    static class TestConfig {
        @Bean
        LoyaltyHubProperties loyaltyHubProperties() {
            LoyaltyHubProperties p = new LoyaltyHubProperties();
            p.setService("test");
            return p;
        }

        @Bean
        LhMetrics lhMetrics() {
            return new LhMetrics(new SimpleMeterRegistry());
        }
        // KafkaAdmin lo fornisce ora LhKafkaConfiguration (bootstrap dalle proprietà).
    }
}
