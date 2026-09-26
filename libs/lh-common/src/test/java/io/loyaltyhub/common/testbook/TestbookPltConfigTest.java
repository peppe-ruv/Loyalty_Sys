package io.loyaltyhub.common.testbook;

import io.loyaltyhub.common.config.LhEnvironmentAliases;
import io.loyaltyhub.common.kafka.LhKafkaConfiguration;
import io.loyaltyhub.common.kafka.LhKafkaHealthIndicator;
import io.loyaltyhub.common.kafka.LhKafkaSecurity;
import io.loyaltyhub.common.kafka.LoyaltyHubProperties;
import io.loyaltyhub.common.kafka.SequenceBackOff;
import io.loyaltyhub.common.metrics.LhMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.health.contributor.Health;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.util.backoff.BackOffExecution;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.loyaltyhub.common.testbook.TestbookPltSupport.outcome;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-PLT §KCF, §HLT e §VAR — configurazione della piattaforma: client Kafka (docs/06 §5, docs/05 §1, ADR-004), sicurezza
 * del broker (docs/06 §5, ADR-025), topic del profilo {@code local}, salute del broker (docs/06 §8) e variabili
 * d'ambiente di docs/11 §8 lette da ogni servizio.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestbookPltConfigTest {

    private final List<LhKafkaHealthIndicator> indicators = new ArrayList<>();

    @AfterAll
    void close() {
        indicators.forEach(LhKafkaHealthIndicator::close);
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/plt/config.csv", numLinesToSkip = 1)
    void config(String id, String description, String kase, String expected) {
        assertThat(outcome(() -> run(kase))).as("%s: %s", id, description).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/plt/env.csv", numLinesToSkip = 1)
    void environment(String id, String description, String variables, String existing, String property, String expected) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, pairs(variables)));
        if (!"-".equals(existing)) {
            // Configurazione del servizio (application.yml): sta sotto l'ambiente, sopra i default della piattaforma.
            env.getPropertySources().addLast(new MapPropertySource("applicationConfig", new HashMap<>(pairs(existing))));
        }
        new LhEnvironmentAliases().postProcessEnvironment(env, new SpringApplication());
        String got = switch (property) {
            case "bind:retryBackoffMs" -> Arrays.toString(Binder.get(env).bindOrCreate("loyaltyhub", LoyaltyHubProperties.class)
                    .getConsumer().getRetryBackoffMs());
            case "bind:security" -> String.valueOf(Binder.get(env).bindOrCreate("loyaltyhub", LoyaltyHubProperties.class)
                    .getKafka().getSecurity());
            default -> String.valueOf(env.getProperty(property));
        };
        assertThat(got).as("%s: %s", id, description).isEqualTo(expected);
    }

    private static Map<String, Object> pairs(String spec) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (spec == null || spec.equals("-")) {
            return out;
        }
        for (String pair : spec.split(";")) {
            int eq = pair.indexOf('=');
            out.put(pair.substring(0, eq).trim(), pair.substring(eq + 1));
        }
        return out;
    }

    private Object run(String kase) throws Exception {
        LoyaltyHubProperties props = new LoyaltyHubProperties();
        props.setService("wallet");
        LhKafkaConfiguration cfg = new LhKafkaConfiguration(props, "broker.example:9092");
        return switch (kase) {
            // ---- produttore (docs/06 §5) ----
            case "producer.acks" -> producer(cfg).get("acks");
            case "producer.idempotence" -> producer(cfg).get("enable.idempotence");
            case "producer.serializers" -> simple(producer(cfg).get("key.serializer")) + " " + simple(producer(cfg).get("value.serializer"));
            case "producer.bootstrap" -> producer(cfg).get("bootstrap.servers");
            // ---- consumatore ----
            case "consumer.group" -> consumer(cfg).get("group.id");
            case "consumer.groupPrefixed" -> {
                LoyaltyHubProperties p = new LoyaltyHubProperties();
                p.setService("lh-hub");
                yield consumer(new LhKafkaConfiguration(p, "b:1")).get("group.id");
            }
            case "consumer.offsetReset" -> consumer(cfg).get("auto.offset.reset");
            case "consumer.autoCommit" -> consumer(cfg).get("enable.auto.commit");
            case "consumer.maxPoll" -> consumer(cfg).get("max.poll.records");
            case "consumer.deserializers" -> simple(consumer(cfg).get("key.deserializer")) + " " + simple(consumer(cfg).get("value.deserializer"));
            // ---- contenitore dei listener ----
            case "container.concurrency" -> container(cfg, true).getConcurrency();
            case "container.ackMode" -> container(cfg, true).getContainerProperties().getAckMode();
            case "container.autoStartupOn" -> container(cfg, true).isAutoStartup();
            case "container.autoStartupOff" -> container(cfg, false).isAutoStartup();
            // ---- ritentativi (docs/04 §5, Q-131) ----
            case "retry.default" -> Arrays.toString(new LoyaltyHubProperties().getConsumer().getRetryBackoffMs());
            case "retry.sequence" -> sequence(new SequenceBackOff(new long[]{1000, 5000}));
            case "retry.empty" -> sequence(new SequenceBackOff(new long[0]));
            case "retry.null" -> sequence(new SequenceBackOff(null));
            // ---- sicurezza (docs/06 §5) ----
            case "security.plaintext" -> LhKafkaSecurity.properties(new LoyaltyHubProperties.Kafka()).get("security.protocol");
            case "security.sslPem.protocol" -> sslPem("Q0E=", "Q0VSVA==", "S0VZ").get("security.protocol");
            case "security.sslPem.types" -> sslPem("Q0E=", "Q0VSVA==", "S0VZ").get("ssl.truststore.type") + " "
                    + sslPem("Q0E=", "Q0VSVA==", "S0VZ").get("ssl.keystore.type");
            case "security.sslPem.values" -> {
                Map<String, Object> p = sslPem(b64("-----CA-----"), b64("-----CERT-----"), b64("-----KEY-----"));
                yield p.get("ssl.truststore.certificates") + " " + p.get("ssl.keystore.certificate.chain") + " "
                        + p.get("ssl.keystore.key");
            }
            case "security.sslPem.trimmed" -> sslPem("  " + b64("CA") + "\n", b64("C"), b64("K")).get("ssl.truststore.certificates");
            case "security.sslPem.blank" -> "[" + sslPem("", null, " ").get("ssl.truststore.certificates") + "]";
            case "security.sasl.scram" -> sasl("SCRAM-SHA-256").get("sasl.jaas.config");
            case "security.sasl.plain" -> sasl("PLAIN").get("sasl.jaas.config");
            case "security.sasl.protocol" -> sasl("SCRAM-SHA-256").get("security.protocol") + " "
                    + sasl("SCRAM-SHA-256").get("sasl.mechanism");
            case "security.defaultMechanism" -> new LoyaltyHubProperties.Sasl().getMechanism();
            case "security.sasl.truststore" -> {
                LoyaltyHubProperties.Kafka k = new LoyaltyHubProperties.Kafka();
                k.setSecurity(LoyaltyHubProperties.Security.SASL_SSL);
                k.getSasl().setUsername("lh-user");
                k.getSasl().setPassword("s3gr3t0");
                k.getSsl().setCaB64(b64("-----CA-----"));
                Map<String, Object> p = LhKafkaSecurity.properties(k);
                yield p.get("ssl.truststore.type") + " " + p.get("ssl.truststore.certificates");
            }
            // ---- topic del profilo local (docs/05 §1, docs/11 §3) ----
            case "local.profile" -> String.join(",", localTopicsClass().getAnnotation(Profile.class).value());
            case "local.names" -> localTopics().stream().map(NewTopic::name).collect(Collectors.joining(" "));
            case "local.partitions" -> localTopics().stream().map(t -> String.valueOf(t.numPartitions())).distinct()
                    .collect(Collectors.joining(","));
            case "local.retention" -> localTopics().stream().map(t -> t.configs().get("retention.ms")).distinct()
                    .collect(Collectors.joining(","));
            // ---- salute del broker (docs/06 §8, docs/07 §8) ----
            case "health.inproc" -> {
                Health h = health(true, Map.of()).health();
                yield h.getStatus() + " " + h.getDetails().get("mode");
            }
            case "health.down" -> {
                Health h = health(false, unreachable()).health();
                yield h.getStatus() + " " + h.getDetails().get("mode");
            }
            case "health.timeout" -> {
                LhKafkaHealthIndicator h = health(false, unreachable());
                long start = System.nanoTime();
                h.health();
                long ms = (System.nanoTime() - start) / 1_000_000;
                // docs/06 §8: timeout 3 s. Il limite superiore lascia margine al primo avvio del client.
                yield ms >= 2_900 && ms < 6_000 ? "≈3 s" : ms + " ms";
            }
            case "health.cached" -> {
                LhKafkaHealthIndicator h = health(false, unreachable());
                Health first = h.health();
                long start = System.nanoTime();
                Health second = h.health();
                long ms = (System.nanoTime() - start) / 1_000_000;
                yield (second.getStatus().equals(first.getStatus()) && ms < 500) ? "in cache" : "ricalcolata in " + ms + " ms";
            }
            case "metrics.dlq" -> {
                SimpleMeterRegistry reg = new SimpleMeterRegistry();
                new LhMetrics(reg).eventDlq("io.loyaltyhub.action.x", "LOOP_GUARD");
                yield reg.get("lh_events_dlq_total").tag("errorCode", "LOOP_GUARD").counter().count();
            }
            default -> throw new IllegalArgumentException("caso sconosciuto: " + kase);
        };
    }

    private static Map<String, Object> producer(LhKafkaConfiguration cfg) {
        return ((DefaultKafkaProducerFactory<String, String>) cfg.lhProducerFactory()).getConfigurationProperties();
    }

    private static Map<String, Object> consumer(LhKafkaConfiguration cfg) {
        return ((DefaultKafkaConsumerFactory<String, String>) cfg.lhConsumerFactory()).getConfigurationProperties();
    }

    private static ConcurrentMessageListenerContainer<String, String> container(LhKafkaConfiguration cfg, boolean autoStartup) {
        KafkaTemplate<String, String> template = cfg.kafkaTemplate(cfg.lhProducerFactory());
        @SuppressWarnings("unchecked")
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                (ConcurrentKafkaListenerContainerFactory<String, String>) cfg.lhKafkaListenerContainerFactory(
                        cfg.lhConsumerFactory(), cfg.lhErrorHandler(template, new LhMetrics(new SimpleMeterRegistry())),
                        autoStartup);
        return factory.createContainer("lh.facts.v1");
    }

    private static String simple(Object cls) {
        return cls instanceof Class<?> c ? c.getSimpleName() : String.valueOf(cls);
    }

    private static String sequence(SequenceBackOff backOff) {
        BackOffExecution ex = backOff.start();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            long next = ex.nextBackOff();
            out.add(next == BackOffExecution.STOP ? "STOP" : String.valueOf(next));
            if (next == BackOffExecution.STOP) {
                break;
            }
        }
        return String.join(" ", out);
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes());
    }

    private static Map<String, Object> sslPem(String ca, String cert, String key) {
        LoyaltyHubProperties.Kafka k = new LoyaltyHubProperties.Kafka();
        k.setSecurity(LoyaltyHubProperties.Security.SSL_PEM);
        k.getSsl().setCaB64(ca);
        k.getSsl().setCertB64(cert);
        k.getSsl().setKeyB64(key);
        return LhKafkaSecurity.properties(k);
    }

    private static Map<String, Object> sasl(String mechanism) {
        LoyaltyHubProperties.Kafka k = new LoyaltyHubProperties.Kafka();
        k.setSecurity(LoyaltyHubProperties.Security.SASL_SSL);
        k.getSasl().setUsername("lh-user");
        k.getSasl().setPassword("s3gr3t0");
        k.getSasl().setMechanism(mechanism);
        return LhKafkaSecurity.properties(k);
    }

    private static Class<?> localTopicsClass() {
        return Arrays.stream(LhKafkaConfiguration.class.getDeclaredClasses())
                .filter(c -> c.getSimpleName().equals("LocalTopics")).findFirst().orElseThrow();
    }

    private static List<NewTopic> localTopics() throws Exception {
        Class<?> cls = localTopicsClass();
        var ctor = cls.getDeclaredConstructor(LoyaltyHubProperties.class);
        ctor.setAccessible(true);
        Object instance = ctor.newInstance(new LoyaltyHubProperties());
        Method m = cls.getDeclaredMethod("lhTopics");
        m.setAccessible(true);
        return newTopics((KafkaAdmin.NewTopics) m.invoke(instance));
    }

    /** {@code NewTopics#getNewTopics} non è pubblico: letto per riflessione (anche dai test dell'hub). */
    @SuppressWarnings("unchecked")
    static List<NewTopic> newTopics(KafkaAdmin.NewTopics topics) throws Exception {
        Method get = KafkaAdmin.NewTopics.class.getDeclaredMethod("getNewTopics");
        get.setAccessible(true);
        return List.copyOf((java.util.Collection<NewTopic>) get.invoke(topics));
    }

    private LhKafkaHealthIndicator health(boolean inProcess, Map<String, Object> cfg) {
        LhKafkaHealthIndicator h = new LhKafkaHealthIndicator(inProcess, cfg);
        indicators.add(h);
        return h;
    }

    private static Map<String, Object> unreachable() {
        return Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1");
    }
}
