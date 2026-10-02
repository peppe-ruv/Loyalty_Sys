package io.loyaltyhub.common.config;

import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.common.approval.ApprovalHistoryStore;
import io.loyaltyhub.common.approval.ApprovalPolicy;
import io.loyaltyhub.common.approval.ApprovalSource;
import io.loyaltyhub.common.approval.ApprovalsController;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.demo.SeedLoader;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.common.inbox.EventRouter;
import io.loyaltyhub.common.inbox.IdempotentHandler;
import io.loyaltyhub.common.inbox.ProcessedEvents;
import io.loyaltyhub.common.kafka.LhKafkaConfiguration;
import io.loyaltyhub.common.kafka.LhKafkaHealthIndicator;
import io.loyaltyhub.common.kafka.LoyaltyHubProperties;
import io.loyaltyhub.common.metrics.LhMetrics;
import io.loyaltyhub.common.outbox.OutboxCleanup;
import io.loyaltyhub.common.outbox.OutboxRelay;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.common.time.BusinessCalendar;
import io.loyaltyhub.common.identity.MemberSubjectLookup;
import io.loyaltyhub.common.web.ActorFilter;
import io.loyaltyhub.common.web.GlobalExceptionHandler;
import io.loyaltyhub.common.web.EndpointAccessInterceptor;
import io.loyaltyhub.common.web.IdentityMode;
import io.loyaltyhub.common.web.MemberBodyAdvice;
import io.loyaltyhub.common.web.MemberEndpointGuard;
import io.loyaltyhub.common.web.MemberPrincipalArgumentResolver;
import io.loyaltyhub.common.web.MemberPrincipals;
import io.loyaltyhub.common.web.NulBodyScopeAdvice;
import io.loyaltyhub.common.web.NulRejectingFilter;
import io.loyaltyhub.common.web.NulRejectingModule;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

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
    @org.springframework.context.annotation.Lazy(false) // docs/06 §5: lo scheduler parte anche con lazy-initialization
    public OutboxRelay outboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, ObjectMapper mapper,
                                   LhMetrics metrics,
                                   org.springframework.core.env.Environment env) {
        int batch = Integer.parseInt(env.getProperty("loyaltyhub.outbox.batch-size", "100"));
        return new OutboxRelay(jdbc, kafka, mapper, metrics, batch);
    }

    @Bean
    @ConditionalOnMissingBean
    @org.springframework.context.annotation.Lazy(false)
    public OutboxCleanup outboxCleanup(JdbcClient jdbc) {
        return new OutboxCleanup(jdbc);
    }

    /** Pulizia di {@code processed_event} oltre 14 giorni (RNF-07, docs/06 §4; SPEC-GAP: Q-335). */
    @Bean
    @ConditionalOnMissingBean
    @org.springframework.context.annotation.Lazy(false)
    public io.loyaltyhub.common.inbox.ProcessedEventCleanup processedEventCleanup(JdbcClient jdbc,
            @Value("${loyaltyhub.processed-event.retention-days:14}") int retentionDays) {
        return new io.loyaltyhub.common.inbox.ProcessedEventCleanup(jdbc, retentionDays);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditPublisher auditPublisher(OutboxWriter outbox, LhEventFactory events, LoyaltyHubProperties props) {
        return new AuditPublisher(outbox, events, props);
    }

    /** Policy delle approvazioni (docs/06 §7): accesa da M7 ({@code LH_APPROVAL_ENABLED}). */
    @Bean
    @ConditionalOnMissingBean
    public ApprovalPolicy approvalPolicy(
            @Value("${loyaltyhub.approval.enabled:true}") boolean enabled,
            @Value("${loyaltyhub.approval.campaign-budget-threshold:100000}") long threshold) {
        return new ApprovalPolicy(enabled, threshold);
    }

    /** {@code GET /v1/approvals} comune: solo dove c'è almeno una fonte (campaign, reward, gamification; tutte nell'hub). */
    @Bean
    @ConditionalOnBean(ApprovalSource.class)
    @ConditionalOnMissingBean
    public ApprovalsController approvalsController(List<ApprovalSource> sources, ApprovalPolicy policy) {
        return new ApprovalsController(sources, policy);
    }

    /** Storico delle transizioni: usato solo dai servizi che hanno la tabella {@code approval_history}. */
    @Bean
    @ConditionalOnMissingBean
    public ApprovalHistoryStore approvalHistoryStore(JdbcClient jdbc) {
        return new ApprovalHistoryStore(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean
    public SeedLoader seedLoader(ObjectMapper mapper) {
        return new SeedLoader(mapper);
    }

    /**
     * Endpoint {@code POST /v1/demo/reset} (docs/06 §1, docs/12 M1.7): registrato solo col profilo {@code demo}.
     * Vive in {@code io.loyaltyhub.common}, fuori dal component-scan dei servizi, quindi va esposto qui.
     */
    @Bean
    @org.springframework.context.annotation.Profile("demo")
    @ConditionalOnMissingBean
    public io.loyaltyhub.common.demo.DemoResetController demoResetController(
            List<io.loyaltyhub.common.demo.DemoResettable> resettables,
            org.springframework.beans.factory.ObjectProvider<AuditPublisher> audit, LoyaltyHubProperties props,
            org.springframework.core.env.Environment env, org.springframework.beans.factory.ObjectProvider<JdbcClient> jdbc,
            Clock clock) {
        // Audit RESET con l'attore (docs/06 §10); GET /v1/demo/info per BO-30.
        return new io.loyaltyhub.common.demo.DemoResetController(resettables, audit.getIfAvailable(), props.getService(),
                env, jdbc.getIfAvailable(), clock);
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

    /**
     * Componente {@code kafka} in {@code /actuator/health} (nome bean → {@code kafka}). Il broker reale
     * (Aiven, ADR-025) risulta {@code UP} quando il cluster risponde; in {@code inproc} (ADR-024) il bus
     * in-process è sempre {@code UP}. Serve allo stato demo (docs/07 §8) che mappa questo componente.
     */
    @Bean(name = "kafkaHealthIndicator", destroyMethod = "close")
    @ConditionalOnMissingBean(name = "kafkaHealthIndicator")
    public LhKafkaHealthIndicator kafkaHealthIndicator(
            org.springframework.kafka.core.KafkaAdmin kafkaAdmin,
            org.springframework.core.env.Environment env) {
        boolean inProcess = env.acceptsProfiles(org.springframework.core.env.Profiles.of("inproc"));
        return new LhKafkaHealthIndicator(inProcess,
                new java.util.HashMap<>(kafkaAdmin.getConfigurationProperties()));
    }

    /**
     * Il byte NUL non arriva mai al database (Q-532 causa (3), F2-SEC-12, ADR-042): {@code 400} su percorso, nomi e
     * valori dei parametri. Gira dopo i filtri di identità e prima del binding; il resolver degli errori del contesto web
     * si legge alla prima richiesta rifiutata.
     */
    @Bean
    @ConditionalOnMissingBean
    public NulRejectingFilter nulRejectingFilter(org.springframework.beans.factory.BeanFactory beans) {
        return new NulRejectingFilter(() -> beans.getBean("handlerExceptionResolver",
                org.springframework.web.servlet.HandlerExceptionResolver.class));
    }

    /**
     * Stesso rifiuto per il corpo JSON: valori e chiavi con un NUL → {@code 400}. Lo raccoglie l'{@code ObjectMapper} di
     * Spring Boot (ogni {@code JacksonModule} è registrato), quindi vale per ogni servizio e per l'hub. Senza
     * {@code ConditionalOnMissingBean} per tipo: {@code JacksonModule} è un tipo condiviso con i moduli di Boot.
     */
    @Bean
    @ConditionalOnMissingBean(name = "lhNulRejectingModule")
    public NulRejectingModule lhNulRejectingModule() {
        return new NulRejectingModule();
    }

    /**
     * Limita il rifiuto del NUL nel JSON alla conversione del corpo della richiesta (Q-532 causa (3)): il parsing che un
     * handler fa da sé di un testo ricevuto (il file d'import JSON, Q-371) non cambia.
     */
    @Bean
    @ConditionalOnMissingBean
    public NulBodyScopeAdvice nulBodyScopeAdvice() {
        return new NulBodyScopeAdvice();
    }

    /** Attore dall'header {@code X-LH-Actor}: solo con {@code loyaltyhub.identity.mode=header} (profilo demo). */
    @Bean
    @ConditionalOnMissingBean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "loyaltyhub.identity.mode",
            havingValue = "header", matchIfMissing = true)
    public ActorFilter actorFilter(LoyaltyHubProperties props, org.springframework.core.env.Environment env) {
        IdentityGuard.check(env);
        return new ActorFilter(props.getService());
    }

    /**
     * Attore dal token (ADR-027, M8.2): {@code loyaltyhub.identity.mode=oidc}. Il JWKS è quello dell'emittente
     * (default Keycloak: {@code <issuer>/protocol/openid-connect/certs}); validatori: firma, scadenza, {@code iss},
     * {@code aud} contenente l'audience dei servizi (default {@code hub}). Con {@code LH_OIDC_MEMBER_ISSUER} (ADR-051)
     * un secondo decoder per il realm dei membri, scelto dal {@code iss} del token.
     */
    @Bean
    @ConditionalOnMissingBean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "loyaltyhub.identity.mode",
            havingValue = "oidc")
    public io.loyaltyhub.common.web.OidcActorFilter oidcActorFilter(LoyaltyHubProperties props,
            org.springframework.core.env.Environment env) {
        IdentityGuard.check(env);
        String issuer = env.getProperty("loyaltyhub.identity.issuer-uri", "").trim();
        String jwks = env.getProperty("loyaltyhub.identity.jwk-set-uri", "").trim();
        String audience = env.getProperty("loyaltyhub.identity.audience", "hub").trim();
        String rolesClaim = env.getProperty("loyaltyhub.identity.roles-claim", "lh_roles").trim();
        org.springframework.security.oauth2.jwt.JwtDecoder decoder = decoder(issuer, jwks, audience);
        String memberIssuer = env.getProperty(IdentityGuard.MEMBER_ISSUER_PROPERTY, "").trim();
        if (!memberIssuer.isEmpty()) {
            // Due realm (ADR-051): ogni token va al decoder del suo emittente, con il JWKS di quel realm.
            String memberJwks = env.getProperty("loyaltyhub.identity.member-jwk-set-uri", "").trim();
            decoder = new io.loyaltyhub.common.web.IssuerRoutingJwtDecoder(java.util.Map.of(
                    issuer, decoder, memberIssuer, decoder(memberIssuer, memberJwks, audience)));
        }
        return new io.loyaltyhub.common.web.OidcActorFilter(props.getService(), decoder, rolesClaim, memberIssuer,
                IdentityGuard.testUsersAllowed(env));
    }

    private static org.springframework.security.oauth2.jwt.JwtDecoder decoder(String issuer, String jwks, String audience) {
        org.springframework.security.oauth2.jwt.NimbusJwtDecoder decoder =
                org.springframework.security.oauth2.jwt.NimbusJwtDecoder
                        .withJwkSetUri(jwks.isEmpty() ? issuer.replaceAll("/+$", "") + "/protocol/openid-connect/certs" : jwks)
                        .build();
        decoder.setJwtValidator(IdentityGuard.validator(issuer, audience));
        return decoder;
    }

    /**
     * Il membro dal token (Q-410, ADR-048): {@code oidc} lo risolve dal {@code sub} con la lookup del modulo, {@code demo}
     * da {@code memberId} o {@code X-LH-Member}. Le lookup dei servizi si leggono alla prima richiesta; la chiave
     * ({@code LH_SUBJECT_KEY}) si decodifica solo in {@code oidc} e, se è presente ma insicura, l'avvio fallisce.
     */
    @Bean
    @ConditionalOnMissingBean
    public MemberPrincipals memberPrincipals(org.springframework.core.env.Environment env,
                                             ObjectProvider<MemberSubjectLookup> lookups) {
        IdentityMode mode = IdentityGuard.mode(env.getProperty("loyaltyhub.identity.mode", "header"));
        byte[] key = mode == IdentityMode.OIDC ? IdentityGuard.subjectKey(env) : null;
        return new MemberPrincipals(mode, key, () -> lookups.orderedStream().toList());
    }

    /** Rifiuta un {@code memberId} nel corpo di un handler del membro in {@code oidc} (Q-553, D6). */
    @Bean
    @ConditionalOnMissingBean
    public MemberBodyAdvice memberBodyAdvice(MemberPrincipals principals) {
        return new MemberBodyAdvice(principals.mode());
    }

    /** Verifica all'avvio la configurazione degli endpoint del membro (regola 22): {@code INSECURE_CONFIG} se insicura. */
    @Bean
    @ConditionalOnMissingBean
    public MemberEndpointGuard memberEndpointGuard(ObjectProvider<RequestMappingHandlerMapping> mappings,
                                                   MemberPrincipals principals,
                                                   org.springframework.core.env.Environment env) {
        return new MemberEndpointGuard(mappings, principals, env);
    }

    @Bean
    public WebMvcConfigurer lhWebMvcConfigurer(ObjectProvider<MemberPrincipals> principals) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new EndpointAccessInterceptor(principals.getObject()));
            }

            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new MemberPrincipalArgumentResolver());
            }
        };
    }
}
