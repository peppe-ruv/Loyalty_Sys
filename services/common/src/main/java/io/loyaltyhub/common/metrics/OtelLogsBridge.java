package io.loyaltyhub.common.metrics;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

/** Collega l'appender OTLP dei log all'OpenTelemetry SDK creato da Spring Boot (RF-118): i log arrivano al collector con trace id. */
@AutoConfiguration
@ConditionalOnBean(OpenTelemetry.class)
public class OtelLogsBridge {
    @Bean
    Object otelLogbackInstaller(OpenTelemetry otel) { OpenTelemetryAppender.install(otel); return new Object(); }
}
