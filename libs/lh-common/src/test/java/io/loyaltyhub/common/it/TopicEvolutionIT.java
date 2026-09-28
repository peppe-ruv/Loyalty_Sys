package io.loyaltyhub.common.it;

import io.loyaltyhub.common.kafka.LhKafkaConfiguration;
import io.loyaltyhub.common.kafka.LhTopicPartitionGuard;
import io.loyaltyhub.common.kafka.LoyaltyHubProperties;
import io.loyaltyhub.common.metrics.LhMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F2-EVT-04 su un broker vero (in-JVM): topic che esistono già e configurazione che cambia. La retention cambiata si
 * applica solo con {@code modify-configs} (acceso di default nel profilo {@code enterprise}); un aumento di partizioni
 * ferma l'avvio salvo {@code allow-partition-increase}; con {@code create=false} l'applicazione non tocca i topic.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TopicEvolutionIT {

    private static final long THREE_DAYS = 259_200_000L;
    private static final long YEAR = 31_536_000_000L;

    private EmbeddedKafkaKraftBroker broker;
    private String bootstrap;

    @BeforeAll
    void up() throws Exception {
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

    /** Contesto col profilo local e topic con prefisso proprio, così i casi non si influenzano. */
    private ApplicationContextRunner runner(String prefix, String profile, String... properties) {
        ApplicationContextRunner r = new ApplicationContextRunner()
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles(profile.split(",")))
                .withPropertyValues("spring.kafka.bootstrap-servers=" + bootstrap,
                        "loyaltyhub.service=test",
                        "loyaltyhub.topics.actions=" + prefix + ".actions",
                        "loyaltyhub.topics.effects=" + prefix + ".effects",
                        "loyaltyhub.topics.facts=" + prefix + ".facts",
                        "loyaltyhub.topics.audit=" + prefix + ".audit",
                        "loyaltyhub.topics.dlq=" + prefix + ".dlq")
                .withUserConfiguration(TestConfig.class);
        return r.withPropertyValues(properties);
    }

    @Test
    void changedRetentionIsAppliedOnlyWithModifyConfigs() throws Exception {
        runner("ret", "local").run(ctx -> assertThat(ctx).hasNotFailed());
        assertThat(retentionOnceSettled("ret.facts", THREE_DAYS)).isEqualTo(THREE_DAYS);

        // Default fuori da enterprise: spento, la retention nuova non arriva al topic esistente (comportamento di oggi).
        runner("ret", "local", "loyaltyhub.topic-settings.retention-ms-by-topic.facts=" + YEAR)
                .run(ctx -> assertThat(ctx).hasNotFailed());
        assertThat(retention("ret.facts")).isEqualTo(THREE_DAYS);

        // Profilo enterprise: acceso senza configurazione esplicita.
        runner("ret", "local,enterprise", "loyaltyhub.topic-settings.retention-ms-by-topic.facts=" + YEAR)
                .run(ctx -> assertThat(ctx).hasNotFailed());
        assertThat(retentionOnceSettled("ret.facts", YEAR)).isEqualTo(YEAR);
        assertThat(retention("ret.actions")).isEqualTo(THREE_DAYS);

        // Spento in modo esplicito anche in enterprise.
        runner("ret", "local,enterprise", "loyaltyhub.topic-settings.modify-configs=false",
                "loyaltyhub.topic-settings.retention-ms-by-topic.facts=" + THREE_DAYS)
                .run(ctx -> assertThat(ctx).hasNotFailed());
        assertThat(retention("ret.facts")).isEqualTo(YEAR);
    }

    @Test
    void partitionIncreaseNeedsAnExplicitAcknowledgement() throws Exception {
        runner("par", "local").run(ctx -> assertThat(ctx).hasNotFailed());
        assertThat(partitionsOnceSettled("par.facts", 2)).isEqualTo(2);

        runner("par", "local", "loyaltyhub.topic-settings.partitions=4").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("PARTITION_INCREASE_NOT_ACKNOWLEDGED")
                    .hasMessageContaining("par.facts 2 → 4");
        });
        assertThat(partitions("par.facts")).as("nessun aumento senza conferma").isEqualTo(2);

        runner("par", "local", "loyaltyhub.topic-settings.partitions=4",
                "loyaltyhub.topic-settings.allow-partition-increase=true").run(ctx -> assertThat(ctx).hasNotFailed());
        assertThat(partitionsOnceSettled("par.facts", 4)).isEqualTo(4);

        // Stessa forma di prima: nessun aumento, avvio normale anche senza conferma.
        runner("par", "local", "loyaltyhub.topic-settings.partitions=4").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void createFalseLeavesTopicsAlone() {
        runner("off", "local", "loyaltyhub.topic-settings.create=false").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(LhTopicPartitionGuard.class);
            assertThat(ctx.getBeansOfType(org.springframework.kafka.core.KafkaAdmin.NewTopics.class)).isEmpty();
        });
    }

    /**
     * In KRaft il controller conferma la creazione di un topic o la modifica della configurazione prima che il broker
     * aggiorni i propri metadati, e le letture dell'Admin passano dal broker. Subito dopo l'avvio il topic può quindi
     * risultare sconosciuto o mostrare ancora il valore precedente: queste letture attendono (al massimo 15 s) che il
     * broker converga sul valore atteso e, se non converge, restituiscono l'ultimo valore letto, così l'asserzione
     * fallisce con il valore vero.
     */
    private long retentionOnceSettled(String topic, long expected) throws Exception {
        return settled(() -> retention(topic), expected);
    }

    private int partitionsOnceSettled(String topic, int expected) throws Exception {
        return settled(() -> partitions(topic), expected);
    }

    private static <T> T settled(Read<T> read, T expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (true) {
            try {
                T value = read.get();
                if (expected.equals(value) || System.nanoTime() > deadline) {
                    return value;
                }
            } catch (ExecutionException e) {
                // Topic non ancora nei metadati del broker (UnknownTopicOrPartitionException): si riprova.
                if (!(e.getCause() instanceof UnknownTopicOrPartitionException) || System.nanoTime() > deadline) {
                    throw e;
                }
            }
            Thread.sleep(100);
        }
    }

    @FunctionalInterface
    private interface Read<T> {
        T get() throws Exception;
    }

    private long retention(String topic) throws Exception {
        try (Admin admin = admin()) {
            ConfigResource r = new ConfigResource(ConfigResource.Type.TOPIC, topic);
            return Long.parseLong(admin.describeConfigs(java.util.List.of(r)).all().get().get(r)
                    .get("retention.ms").value());
        }
    }

    private int partitions(String topic) throws Exception {
        try (Admin admin = admin()) {
            return admin.describeTopics(java.util.List.of(topic)).allTopicNames().get().get(topic).partitions().size();
        }
    }

    private Admin admin() {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LoyaltyHubProperties.class)
    @Import(LhKafkaConfiguration.class)
    static class TestConfig {
        @Bean
        LhMetrics lhMetrics() {
            return new LhMetrics(new SimpleMeterRegistry());
        }
    }
}
