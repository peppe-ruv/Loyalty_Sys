package io.loyaltyhub.common.config;

import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.demo.SeedLoader;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.common.inbox.EventRouter;
import io.loyaltyhub.common.inbox.IdempotentHandler;
import io.loyaltyhub.common.inbox.ProcessedEvents;
import io.loyaltyhub.common.kafka.LhKafkaConfiguration;
import io.loyaltyhub.common.kafka.LoyaltyHubProperties;
import io.loyaltyhub.common.metrics.LhMetrics;
import io.loyaltyhub.common.outbox.OutboxCleanup;
import io.loyaltyhub.common.outbox.OutboxRelay;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.common.time.BusinessCalendar;
import io.loyaltyhub.common.web.ActorFilter;
import io.loyaltyhub.common.web.GlobalExceptionHandler;
import io.loyaltyhub.common.web.RequiresRoleInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;
import java.util.List;

/**
 * Auto-configurazione di {@code lh-common} (docs/06 §1): registra envelope, outbox/inbox, idempotenza,
 * Kafka, errori RFC 9457, actor, seed loader. I bean sono {@code ConditionalOnMissingBean} così un servizio
 * può sovrascriverli.
 */
@AutoConfiguration
@EnableConfigurationProperties(LoyaltyHubProperties.class)
@EnableScheduling
@Import(LhKafkaConfiguration.class)
public class LhCommonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    public BusinessCalendar businessCalendar(Clock clock) {
        return new BusinessCalendar(clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public LhEventFactory lhEventFactory(Clock clock, LoyaltyHubProperties props) {
        return new LhEventFactory(clock, props.getService());
    }

    @Bean
    @ConditionalOnMissingBean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public LhMetrics lhMetrics(MeterRegistry registry) {
        return new LhMetrics(registry);
    }

    // L'ObjectMapper è quello di Spring Boot (Jackson 3): l'envelope LhEvent porta già le annotazioni
    // @JsonInclude(NON_NULL)/@JsonIgnoreProperties. La configurazione fine (inclusion, unknown) si imposta
    // via spring.jackson.* nei servizi (docs/06 §2). LhJson resta per gli usi non-Spring (test).

    @Bean
    @ConditionalOnMissingBean
    public OutboxWriter outboxWriter(JdbcClient jdbc, ObjectMapper mapper, LoyaltyHubProperties props) {
        return new OutboxWriter(jdbc, mapper, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProcessedEvents processedEvents(JdbcClient jdbc) {
        return new ProcessedEvents(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotentHandler idempotentHandler(ProcessedEvents processedEvents) {
        return new IdempotentHandler(processedEvents);
    }

    @Bean
    @ConditionalOnMissingBean
    public EventRouter eventRouter(LoyaltyHubProperties props, List<EventHandler> handlers,
                                   IdempotentHandler idempotent, LhMetrics metrics) {
        String consumer = props.getService().startsWith("lh-") ? props.getService() : "lh-" + props.getService();
        return new EventRouter(consumer, handlers, idempotent, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxRelay outboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, ObjectMapper mapper,
                                   LhMetrics metrics,
                                   org.springframework.core.env.Environment env) {
        int batch = Integer.parseInt(env.getProperty("loyaltyhub.outbox.batch-size", "100"));
        return new OutboxRelay(jdbc, kafka, mapper, metrics, batch);
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxCleanup outboxCleanup(JdbcClient jdbc) {
        return new OutboxCleanup(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditPublisher auditPublisher(OutboxWriter outbox, LhEventFactory events, LoyaltyHubProperties props) {
        return new AuditPublisher(outbox, events, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public SeedLoader seedLoader(ObjectMapper mapper) {
        return new SeedLoader(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public io.loyaltyhub.common.event.JsonSchemaValidator jsonSchemaValidator() {
        return new io.loyaltyhub.common.event.JsonSchemaValidator();
    }

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public ActorFilter actorFilter(LoyaltyHubProperties props) {
        return new ActorFilter(props.getService());
    }

    @Bean
    public WebMvcConfigurer lhWebMvcConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new RequiresRoleInterceptor());
            }
        };
    }
}
