package io.loyaltyhub.hub;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.registry.otlp.AggregationTemporality;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsExportAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Configurazione dell'esportazione OTLP delle metriche dell'hub (M8.6a, F2-OBS-01, ADR-012, ADR-036, Q-520).
 * Carica il <strong>primo documento di {@code hub.yml}</strong> (quello che vale in ogni profilo) e ci monta le
 * auto-configurazioni di Boot per le metriche: nessun database né broker. Verifica che:
 * <ul>
 *   <li>di default (demo ospitata) non esiste alcun esportatore OTLP e l'SDK OpenTelemetry è quello "spento" di Boot,
 *       senza esportatori (costo zero, ADR-044: nessuna telemetria in uscita);</li>
 *   <li>con {@code LH_OTEL_METRICS_ENABLED=true} c'è un solo {@link OtlpMeterRegistry}, in secondi, con gli attributi
 *       della risorsa attesi, e il registro primario è il composito che lo contiene, così le metriche {@code lh_*} di
 *       {@code LhMetrics} (registrate sul {@code SimpleMeterRegistry} di lh-common) arrivano anche in OTLP;</li>
 *   <li>i bucket delle soglie SLO (500 ms e 5 s) sono presenti sulle richieste HTTP del server;</li>
 *   <li>le variabili {@code OTEL_*} standard non comandano l'hub: {@code management.opentelemetry
 *       .map-environment-variables=false} e {@code service.name} fissato a {@code hub} (ADR-044, regola 22, Q-520);
 *       la prova è comportamentale: si esegue l'{@code EnvironmentPostProcessor} di Boot con variabili {@code OTEL_*}
 *       ostili e si controlla che nulla cambi, con un controllo negativo che mostra che senza l'interruttore le
 *       stesse variabili invece comanderebbero.</li>
 * </ul>
 * L'invio reale a un ricevitore è in {@code HubOtlpMetricsIT}.
 */
class HubObservabilityConfigTest {

    /** Nome del bean con cui Boot 4.1 espone l'SDK quando {@code management.opentelemetry.enabled=false}. */
    private static final String DISABLED_SDK_BEAN = "disabledOpenTelemetrySdk";

    /**
     * Destinazione di loopback su una porta chiusa (discard, 9): con l'esportazione accesa il registro OTLP pubblica
     * anche alla chiusura del contesto, e il default {@code http://localhost:4318} sarebbe un tentativo di rete vero
     * fatto da un test unitario.
     */
    private static final String CLOSED_LOOPBACK_URL_VALUE = "http://127.0.0.1:9/v1/metrics";
    private static final String CLOSED_LOOPBACK_URL = "LH_OTEL_METRICS_URL=" + CLOSED_LOOPBACK_URL_VALUE;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(hubYmlFirstDocument()))
            .withConfiguration(AutoConfigurations.of(
                    MetricsAutoConfiguration.class,
                    CompositeMeterRegistryAutoConfiguration.class,
                    SimpleMetricsExportAutoConfiguration.class,
                    OtlpMetricsExportAutoConfiguration.class,
                    OpenTelemetrySdkAutoConfiguration.class))
            .withUserConfiguration(LhCommonLikeConfiguration.class);

    /** Come {@code LhCommonAutoConfiguration}: un {@code SimpleMeterRegistry} definito dall'applicazione. */
    @Configuration(proxyBeanMethods = false)
    static class LhCommonLikeConfiguration {
        @Bean
        SimpleMeterRegistry lhRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Test
    void offByDefault_noOtlpRegistryAndNoOpenTelemetrySdk() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OtlpMeterRegistry.class);
            // management.opentelemetry.enabled=false: Boot espone un SDK "spento" (senza risorsa, processori né
            // esportatori), non quello configurato.
            assertThat(context.getBeanNamesForType(OpenTelemetry.class)).containsExactly(DISABLED_SDK_BEAN);
            assertThat(context.getBean(MeterRegistry.class)).isSameAs(context.getBean("lhRegistry"));
        });
    }

    @Test
    void enabled_exportsInSecondsWithResourceAttributesAndLhMetricsReachOtlp() {
        runner.withPropertyValues(
                        "LH_OTEL_METRICS_ENABLED=true",
                        CLOSED_LOOPBACK_URL,
                        "LH_OTEL_SERVICE_NAMESPACE=loyaltyhub-prod",
                        "LH_OTEL_INSTANCE_ID=pod-1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(OtlpMeterRegistry.class)).hasSize(1);
                    assertThat(context.getBean(OpenTelemetry.class)).isInstanceOf(OpenTelemetrySdk.class);
                    assertThat(context.getBeanNamesForType(OpenTelemetry.class)).doesNotContain(DISABLED_SDK_BEAN);

                    OtlpConfig config = context.getBean(OtlpConfig.class);
                    assertThat(config.url()).isEqualTo(CLOSED_LOOPBACK_URL_VALUE);
                    assertThat(config.step()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(config.baseTimeUnit()).isEqualTo(TimeUnit.SECONDS);
                    assertThat(config.resourceAttributes())
                            .containsEntry("service.name", "hub")
                            .containsEntry("service.namespace", "loyaltyhub-prod")
                            .containsEntry("service.instance.id", "pod-1");

                    // Il registro di lh-common (dove LhMetrics registra lh_*) non deve restare fuori da OTLP:
                    // il primario è il composito che contiene sia il registro semplice sia quello OTLP.
                    MeterRegistry primary = context.getBean(MeterRegistry.class);
                    assertThat(primary).isInstanceOf(CompositeMeterRegistry.class);
                    assertThat(((CompositeMeterRegistry) primary).getRegistries())
                            .hasAtLeastOneElementOfType(OtlpMeterRegistry.class)
                            .contains(context.getBean("lhRegistry", SimpleMeterRegistry.class));
                });
    }

    @Test
    void sloBucketsOfHttpServerRequestsIncludeHalfASecondAndFiveSeconds() {
        // Sia con l'esportazione spenta (registro semplice) sia accesa (composito).
        for (String enabled : List.of("false", "true")) {
            runner.withPropertyValues("LH_OTEL_METRICS_ENABLED=" + enabled, CLOSED_LOOPBACK_URL).run(context -> {
                assertThat(context).hasNotFailed();
                MeterRegistry primary = context.getBean(MeterRegistry.class);
                Timer timer = Timer.builder("http.server.requests").register(primary);
                timer.record(Duration.ofMillis(100));

                List<Double> boundsInSeconds = Arrays.stream(timer.takeSnapshot().histogramCounts())
                        .map(bucket -> bucket.bucket(TimeUnit.SECONDS))
                        .toList();
                assertThat(boundsInSeconds)
                        .as("bucket in secondi con esportazione=" + enabled)
                        .contains(0.5, 5.0);
            });
        }
    }

    @Test
    void openTelemetryEnvironmentVariablesDoNotDriveTheHub() {
        // Boot mappa le OTEL_* (OTEL_METRICS_EXPORTER, OTEL_EXPORTER_OTLP_ENDPOINT, ..._TEMPORALITY_PREFERENCE,
        // OTEL_SERVICE_NAME) in una fonte con precedenza su hub.yml: senza questo interruttore un webhook di
        // piattaforma accenderebbe l'invio, aggirerebbe INSECURE_CONFIG e cambierebbe il `job` di Prometheus.
        for (String enabled : List.of("false", "true")) {
            runner.withPropertyValues("LH_OTEL_METRICS_ENABLED=" + enabled, CLOSED_LOOPBACK_URL).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getEnvironment()
                        .getProperty("management.opentelemetry.map-environment-variables", Boolean.class))
                        .as("map-environment-variables con esportazione=" + enabled)
                        .isFalse();
                assertThat(context.getEnvironment()
                        .getProperty("management.opentelemetry.resource-attributes[service.name]"))
                        .as("service.name fissato a hub")
                        .isEqualTo("hub");
                assertThat(context.getEnvironment()
                        .getProperty("management.otlp.metrics.export.aggregation-temporality"))
                        .as("temporalità cumulativa")
                        .isEqualTo("cumulative");
            });
        }
    }

    /**
     * Variabili {@code OTEL_*} ostili, come quelle che un webhook di piattaforma o l'OpenTelemetry Operator possono
     * iniettare: accenderebbero l'invio, lo punterebbero altrove, cambierebbero il {@code job} e la temporalità.
     */
    private static final Map<String, String> HOSTILE_OTEL_ENVIRONMENT = Map.of(
            "OTEL_METRICS_EXPORTER", "otlp",
            "OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector.vendor.example:4318",
            "OTEL_SERVICE_NAME", "other",
            "OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE", "delta");

    @Test
    void hostileOpenTelemetryEnvironmentVariablesAreIgnoredWhenExportIsOff() {
        runner.withInitializer(context -> applyOpenTelemetryEnvironmentPostProcessor(context.getEnvironment()))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(OtlpMeterRegistry.class);
                    assertThat(context.getBeanNamesForType(OpenTelemetry.class)).containsExactly(DISABLED_SDK_BEAN);
                    assertThat(context.getEnvironment().getProperty("management.otlp.metrics.export.enabled", Boolean.class))
                            .as("nessun OTEL_* accende l'invio")
                            .isFalse();
                });
    }

    @Test
    void hostileOpenTelemetryEnvironmentVariablesDoNotChangeUrlServiceNameOrTemporalityWhenExportIsOn() {
        runner.withPropertyValues("LH_OTEL_METRICS_ENABLED=true", CLOSED_LOOPBACK_URL)
                .withInitializer(context -> applyOpenTelemetryEnvironmentPostProcessor(context.getEnvironment()))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(OtlpMeterRegistry.class)).hasSize(1);
                    OtlpConfig config = context.getBean(OtlpConfig.class);
                    assertThat(config.url()).isEqualTo(CLOSED_LOOPBACK_URL_VALUE);
                    assertThat(config.resourceAttributes()).containsEntry("service.name", "hub");
                    assertThat(config.aggregationTemporality()).isEqualTo(AggregationTemporality.CUMULATIVE);
                });
    }

    /**
     * Controllo negativo: la prova sopra non è vuota. Con l'interruttore acceso (il default di Boot) lo stesso
     * post-processore mappa le stesse variabili sopra {@code hub.yml}; solo l'ambiente, nessun contesto né rete.
     */
    @Test
    void controlWithMappingEnabledTheSameVariablesWouldTakeOver() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(hubYmlFirstDocument());
        environment.getPropertySources().addFirst(new MapPropertySource("control",
                Map.of("management.opentelemetry.map-environment-variables", "true")));

        applyOpenTelemetryEnvironmentPostProcessor(environment);

        assertThat(environment.getProperty("management.otlp.metrics.export.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("management.otlp.metrics.export.url"))
                .startsWith("http://collector.vendor.example:4318");
        assertThat(environment.getProperty("management.otlp.metrics.export.aggregation-temporality"))
                .isEqualToIgnoringCase("delta");
        // OTEL_SERVICE_NAME non è mappato da questo post-processore in Boot 4.1.1: service.name resta fissato a `hub`
        // in hub.yml come difesa se una versione futura lo mappasse (verificato sul contesto nei test sopra).
    }

    /**
     * Esegue il post-processore di Boot che mappa le {@code OTEL_*} (un {@code ApplicationContextRunner} non lo
     * esegue, e legge {@code System.getenv}, che un test non può modificare): si costruisce con l'accesso alle
     * variabili di Boot su una mappa. Riflessione su tipi non pubblici di Boot 4.1: se cambiano, il test fallisce e va
     * riscritto, senza lasciare la protezione priva di prova.
     */
    private static void applyOpenTelemetryEnvironmentPostProcessor(ConfigurableEnvironment environment) {
        try {
            String pkg = "org.springframework.boot.opentelemetry.autoconfigure.";
            Class<?> variables = Class.forName(pkg + "OpenTelemetryEnvironmentVariables");
            Class<?> processorType = Class.forName(pkg + "OpenTelemetryEnvironmentVariableEnvironmentPostProcessor");
            DeferredLogFactory logs = new DeferredLogs();
            Method forMap = variables.getDeclaredMethod("forMap", DeferredLogFactory.class, Map.class);
            forMap.setAccessible(true);
            Object lookup = forMap.invoke(null, logs, new LinkedHashMap<>(HOSTILE_OTEL_ENVIRONMENT));
            Constructor<?> constructor = processorType.getDeclaredConstructor(DeferredLogFactory.class, variables);
            constructor.setAccessible(true);
            ((EnvironmentPostProcessor) constructor.newInstance(logs, lookup))
                    .postProcessEnvironment(environment, new SpringApplication());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("post-processore OTEL_* di Boot non raggiungibile: aggiornare il test", e);
        }
    }

    private static PropertySource<?> hubYmlFirstDocument() {
        try {
            return new YamlPropertySourceLoader().load("hub", new ClassPathResource("hub.yml")).get(0);
        } catch (IOException e) {
            throw new IllegalStateException("hub.yml non leggibile dal classpath", e);
        }
    }
}
