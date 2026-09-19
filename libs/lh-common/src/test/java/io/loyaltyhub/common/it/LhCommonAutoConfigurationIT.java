package io.loyaltyhub.common.it;

import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.config.LhCommonAutoConfiguration;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.inbox.EventRouter;
import io.loyaltyhub.common.inbox.IdempotentHandler;
import io.loyaltyhub.common.outbox.OutboxRelay;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.common.web.GlobalExceptionHandler;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica che l'auto-configurazione di {@code lh-common} monti i bean del contratto (docs/06 §1)
 * dentro un contesto Spring reale — la stessa cablatura che il servizio archetipo userà in M0.5.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LhCommonAutoConfigurationIT {

    private static EmbeddedPostgres pg;

    @BeforeAll
    void up() throws Exception {
        pg = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    void down() throws Exception {
        if (pg != null) {
            pg.close();
        }
    }

    @Test
    void wiresCoreBeans() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LhCommonAutoConfiguration.class))
                .withUserConfiguration(CollaboratorsConfig.class)
                // Relay fermo durante il test (niente Kafka reale qui): scheduling oltre l'orizzonte del test.
                .withPropertyValues(
                        "loyaltyhub.service=wallet",
                        "loyaltyhub.outbox.relay-interval-ms=3600000",
                        "spring.kafka.bootstrap-servers=localhost:9092")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OutboxWriter.class);
                    assertThat(context).hasSingleBean(OutboxRelay.class);
                    assertThat(context).hasSingleBean(IdempotentHandler.class);
                    assertThat(context).hasSingleBean(EventRouter.class);
                    assertThat(context).hasSingleBean(AuditPublisher.class);
                    assertThat(context).hasSingleBean(LhEventFactory.class);
                    assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
                    assertThat(context).hasSingleBean(KafkaTemplate.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CollaboratorsConfig {
        @Bean
        DataSource dataSource() {
            return pg.getPostgresDatabase();
        }

        @Bean
        JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }
    }
}
