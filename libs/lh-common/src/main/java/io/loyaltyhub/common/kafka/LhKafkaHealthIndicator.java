package io.loyaltyhub.common.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Health del broker per l'actuator: si presenta come componente {@code kafka} in {@code /actuator/health}
 * (lo consuma lo stato demo, docs/07 §8, {@code web/app/api/demo/status}). In profilo {@code inproc}
 * (ADR-024) non c'è broker e il bus in-process è sempre disponibile → {@code UP}. Con un broker reale
 * (Aiven, ADR-025) interroga il cluster via {@link AdminClient} con timeout breve, riusando lo stesso
 * {@code AdminClient} tra le chiamate; qualunque errore è tradotto in {@code DOWN} senza propagarsi.
 */
public class LhKafkaHealthIndicator implements HealthIndicator, AutoCloseable {

    private static final int TIMEOUT_MS = 2000;

    private final boolean inProcess;
    private final Map<String, Object> adminConfig;
    private volatile AdminClient admin;

    public LhKafkaHealthIndicator(boolean inProcess, Map<String, Object> adminConfig) {
        this.inProcess = inProcess;
        this.adminConfig = adminConfig;
    }

    @Override
    public Health health() {
        if (inProcess) {
            return Health.up().withDetail("mode", "in-process").build();
        }
        try {
            DescribeClusterResult cluster = admin().describeCluster();
            String clusterId = cluster.clusterId().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            int nodes = cluster.nodes().get(TIMEOUT_MS, TimeUnit.MILLISECONDS).size();
            return Health.up().withDetail("mode", "broker")
                    .withDetail("clusterId", clusterId).withDetail("nodes", nodes).build();
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return Health.down().withDetail("mode", "broker")
                    .withDetail("error", cause.getClass().getSimpleName()).build();
        }
    }

    private AdminClient admin() {
        AdminClient current = admin;
        if (current == null) {
            synchronized (this) {
                if (admin == null) {
                    Map<String, Object> cfg = new HashMap<>(adminConfig);
                    cfg.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, TIMEOUT_MS);
                    cfg.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, TIMEOUT_MS);
                    admin = AdminClient.create(cfg);
                }
                current = admin;
            }
        }
        return current;
    }

    @Override
    public void close() {
        AdminClient current = admin;
        if (current != null) {
            current.close(Duration.ofSeconds(1));
        }
    }
}
