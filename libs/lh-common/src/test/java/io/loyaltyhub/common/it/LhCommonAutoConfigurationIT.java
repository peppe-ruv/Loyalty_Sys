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

    private ApplicationContextRunner runner(String... extra) {
        java.util.List<String> props = new java.util.ArrayList<>(java.util.List.of(
                "loyaltyhub.service=wallet",
                "loyaltyhub.outbox.relay-interval-ms=3600000",
                "spring.kafka.bootstrap-servers=localhost:9092"));
        props.addAll(java.util.List.of(extra));
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LhCommonAutoConfiguration.class))
                .withUserConfiguration(CollaboratorsConfig.class)
                .withPropertyValues(props.toArray(String[]::new));
    }

    /** Il membro dal token (Q-410, ADR-048): i bean ci sono in ogni profilo; la chiave conta solo in oidc. */
    @Test
    void wiresTheMemberFromTheTokenInTheDemoProfile() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(io.loyaltyhub.common.web.MemberPrincipals.class);
            assertThat(context).hasSingleBean(io.loyaltyhub.common.web.MemberBodyAdvice.class);
            assertThat(context).hasSingleBean(io.loyaltyhub.common.web.MemberEndpointGuard.class);
            assertThat(context.getBean(io.loyaltyhub.common.web.MemberPrincipals.class).mode())
                    .isEqualTo(io.loyaltyhub.common.web.IdentityMode.HEADER);
        });
        // In demo la chiave, anche se malformata, non si legge.
        runner("loyaltyhub.identity.subject-key=non-base64!!").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void oidcNeedsAStrongSubjectKeyWhenOneIsConfigured() {
        String issuer = "loyaltyhub.identity.issuer-uri=https://idp.example.test/realms/loyaltyhub";
        String good = java.util.Base64.getEncoder().encodeToString(new byte[32]);
        runner("loyaltyhub.identity.mode=oidc", issuer, "loyaltyhub.identity.subject-key=" + good).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(io.loyaltyhub.common.web.MemberPrincipals.class).mode())
                    .isEqualTo(io.loyaltyhub.common.web.IdentityMode.OIDC);
        });
        // Chiave corta o non base64: l'avvio fallisce con INSECURE_CONFIG, mai un avviso (regola 22).
        String shortKey = java.util.Base64.getEncoder().encodeToString(new byte[16]);
        runner("loyaltyhub.identity.mode=oidc", issuer, "loyaltyhub.identity.subject-key=" + shortKey).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("INSECURE_CONFIG");
        });
        runner("loyaltyhub.identity.mode=oidc", issuer, "loyaltyhub.identity.subject-key=non-base64!!").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("INSECURE_CONFIG");
        });
        // Nessun endpoint del membro (l'hub di oggi in enterprise): senza chiave si avvia.
        runner("loyaltyhub.identity.mode=oidc", issuer).run(context -> assertThat(context).hasNotFailed());
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

        // Fuori da un contesto Boot completo non c'è l'ObjectMapper di Jackson: lo forniamo noi.
        @Bean
        tools.jackson.databind.ObjectMapper objectMapper() {
            return io.loyaltyhub.common.event.LhJson.create();
        }
    }
}
