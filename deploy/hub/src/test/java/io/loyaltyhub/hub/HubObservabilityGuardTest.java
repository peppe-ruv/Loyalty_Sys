package io.loyaltyhub.hub;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Il profilo enterprise non invia metriche in chiaro fuori dal cluster (regola 22, ADR-044, Q-520). */
class HubObservabilityGuardTest {

    private static MockEnvironment env(String profile, String enabled, String url) {
        MockEnvironment env = new MockEnvironment()
                .withProperty("management.otlp.metrics.export.enabled", enabled);
        if (url != null) {
            env.setProperty("management.otlp.metrics.export.url", url);
        }
        env.setActiveProfiles(profile);
        return env;
    }

    @Test
    void enterpriseRefusesPlainHttpOutsideTheCluster() {
        for (String url : new String[] {
                "http://collector.vendor.example:4318/v1/metrics",
                "http://10.0.0.5:4318/v1/metrics",
                "http://otel.monitoring.svc.cluster.local.evil.com:4318/v1/metrics",
                "http://localhost.evil.example/v1/metrics",
                "HTTP://collector.vendor.example:4318/v1/metrics"}) {
            assertThatThrownBy(() -> HubObservabilityGuard.check(env("enterprise", "true", url)))
                    .as(url)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("INSECURE_CONFIG");
        }
    }

    @Test
    void enterpriseAllowsClusterServicesAndHttps() {
        for (String url : new String[] {
                "http://lh-otel-collector:4318/v1/metrics",
                "http://otelcol:4318/v1/metrics",
                "http://lh-otel-collector.loyaltyhub.svc:4318/v1/metrics",
                "http://lh-otel-collector.loyaltyhub.svc.cluster.local:4318/v1/metrics",
                "https://collector.vendor.example:4318/v1/metrics"}) {
            assertThatCode(() -> HubObservabilityGuard.check(env("enterprise", "true", url)))
                    .as(url)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void offByDefaultAndDemoAreNotChecked() {
        String outside = "http://collector.vendor.example:4318/v1/metrics";
        // esportazione spenta (default): nulla esce, nulla da controllare
        assertThatCode(() -> HubObservabilityGuard.check(env("enterprise", "false", outside))).doesNotThrowAnyException();
        // profilo demo: nessun obbligo di cifratura
        assertThatCode(() -> HubObservabilityGuard.check(env("demo", "true", outside))).doesNotThrowAnyException();
    }
}
