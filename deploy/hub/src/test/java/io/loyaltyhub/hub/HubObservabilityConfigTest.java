package io.loyaltyhub.hub;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsExportAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
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
 *   <li>i bucket delle soglie SLO (500 ms e 5 s) sono presenti sulle richieste HTTP del server.</li>
 * </ul>
 * L'invio reale a un ricevitore è in {@code HubOtlpMetricsIT}.
 */
class HubObservabilityConfigTest {

    /** Nome del bean con cui Boot 4.1 espone l'SDK quando {@code management.opentelemetry.enabled=false}. */
    private static final String DISABLED_SDK_BEAN = "disabledOpenTelemetrySdk";

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
                        "LH_OTEL_METRICS_URL=http://collector:4318/v1/metrics",
                        "LH_OTEL_SERVICE_NAMESPACE=loyaltyhub-prod",
                        "LH_OTEL_INSTANCE_ID=pod-1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(OtlpMeterRegistry.class)).hasSize(1);
                    assertThat(context.getBean(OpenTelemetry.class)).isInstanceOf(OpenTelemetrySdk.class);
                    assertThat(context.getBeanNamesForType(OpenTelemetry.class)).doesNotContain(DISABLED_SDK_BEAN);

                    OtlpConfig config = context.getBean(OtlpConfig.class);
                    assertThat(config.url()).isEqualTo("http://collector:4318/v1/metrics");
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
            runner.withPropertyValues("LH_OTEL_METRICS_ENABLED=" + enabled).run(context -> {
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

    private static PropertySource<?> hubYmlFirstDocument() {
        try {
            return new YamlPropertySourceLoader().load("hub", new ClassPathResource("hub.yml")).get(0);
        } catch (IOException e) {
            throw new IllegalStateException("hub.yml non leggibile dal classpath", e);
        }
    }
}
