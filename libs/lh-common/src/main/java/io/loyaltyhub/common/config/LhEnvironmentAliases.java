package io.loyaltyhub.common.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Variabili d'ambiente della piattaforma (docs/11 §8, docs/06 §5) lette da ogni servizio e dall'hub, senza ripeterle nei
 * {@code application.yml}: {@code LH_CONSUMER_RETRY_BACKOFF_MS}, {@code LH_JOBS_ENABLED}, {@code LH_APPROVAL_ENABLED},
 * {@code KAFKA_*}, {@code DB_USER}, {@code DB_URL_DIRECT}. Ogni variabile valorizzata diventa la proprietà
 * {@code loyaltyhub.*}/{@code spring.*} corrispondente, con la precedenza dell'ambiente (sopra la configurazione del
 * servizio). Un nome alternativo già usato dal servizio ({@code DB_USERNAME}, {@code SPRING_KAFKA_BOOTSTRAP_SERVERS},
 * ADR-025) prevale.
 *
 * <p>Aggiunge anche i default comuni di salute (docs/11 §6): liveness senza dipendenze esterne, readiness con
 * {@code db} e {@code kafka}; stanno sotto ogni altra configurazione.
 */
public class LhEnvironmentAliases implements EnvironmentPostProcessor, Ordered {

    static final String ALIASES_SOURCE = "lhEnvironmentAliases";
    static final String DEFAULTS_SOURCE = "lhPlatformDefaults";

    /** Variabile d'ambiente → proprietà, salvo che sia valorizzata una delle variabili in {@code unlessPresent}. */
    private record Alias(String variable, String property, List<String> unlessPresent) {
        Alias(String variable, String property) {
            this(variable, property, List.of());
        }
    }

    private static final List<Alias> ALIASES = List.of(
            new Alias("LH_CONSUMER_RETRY_BACKOFF_MS", "loyaltyhub.consumer.retry-backoff-ms"),
            new Alias("LH_JOBS_ENABLED", "loyaltyhub.jobs.enabled"),
            new Alias("LH_APPROVAL_ENABLED", "loyaltyhub.approval.enabled"),
            new Alias("KAFKA_SECURITY", "loyaltyhub.kafka.security"),
            new Alias("KAFKA_SSL_CA_B64", "loyaltyhub.kafka.ssl.ca-b64"),
            new Alias("KAFKA_SSL_CERT_B64", "loyaltyhub.kafka.ssl.cert-b64"),
            new Alias("KAFKA_SSL_KEY_B64", "loyaltyhub.kafka.ssl.key-b64"),
            new Alias("KAFKA_SASL_USERNAME", "loyaltyhub.kafka.sasl.username"),
            new Alias("KAFKA_SASL_PASSWORD", "loyaltyhub.kafka.sasl.password"),
            new Alias("KAFKA_SASL_MECHANISM", "loyaltyhub.kafka.sasl.mechanism"),
            new Alias("KAFKA_BOOTSTRAP", "spring.kafka.bootstrap-servers", List.of("SPRING_KAFKA_BOOTSTRAP_SERVERS")),
            new Alias("DB_USER", "spring.datasource.username", List.of("DB_USERNAME", "SPRING_DATASOURCE_USERNAME")),
            new Alias("DB_URL_DIRECT", "spring.flyway.url", List.of("SPRING_FLYWAY_URL")));

    private static final Map<String, Object> DEFAULTS = Map.of(
            "management.endpoint.health.group.liveness.include", "livenessState",
            "management.endpoint.health.group.readiness.include", "readinessState,db,kafka");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        MutablePropertySources sources = environment.getPropertySources();
        PropertySource<?> system = sources.get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        if (system != null && !sources.contains(ALIASES_SOURCE)) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            for (Alias alias : ALIASES) {
                String value = valued(system, alias.variable());
                boolean overridden = alias.unlessPresent().stream().anyMatch(v -> valued(system, v) != null);
                if (value != null && !overridden) {
                    mapped.put(alias.property(), value);
                }
            }
            if (!mapped.isEmpty()) {
                sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new MapPropertySource(ALIASES_SOURCE, mapped));
            }
        }
        // I gruppi liveness/readiness esistono solo con le sonde accese (tutti i servizi e l'hub le accendono):
        // senza, i contributi livenessState/readinessState mancherebbero e l'avvio fallirebbe.
        boolean probes = "true".equalsIgnoreCase(environment.getProperty("management.endpoint.health.probes.enabled", "false"));
        if (probes && !sources.contains(DEFAULTS_SOURCE)) {
            MapPropertySource defaults = new MapPropertySource(DEFAULTS_SOURCE, DEFAULTS);
            if (sources.contains("defaultProperties")) {
                sources.addBefore("defaultProperties", defaults);
            } else {
                sources.addLast(defaults);
            }
        }
    }

    private static String valued(PropertySource<?> system, String name) {
        Object v = system.getProperty(name);
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** Dopo il caricamento della configurazione dei servizi: i default comuni finiscono sotto di essa. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 10;
    }
}
