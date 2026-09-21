package io.loyaltyhub.common.kafka;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Health del broker (docs/07 §8): UP in-process; DOWN se il broker reale non risponde. */
class LhKafkaHealthIndicatorTest {

    @Test
    void inProcessIsAlwaysUp() {
        try (LhKafkaHealthIndicator h = new LhKafkaHealthIndicator(true, Map.of())) {
            Health health = h.health();
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry("mode", "in-process");
        }
    }

    @Test
    void unreachableBrokerIsDown() {
        // Bootstrap inesistente: describeCluster va in timeout entro il limite breve → DOWN, senza propagare.
        Map<String, Object> cfg = Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1");
        try (LhKafkaHealthIndicator h = new LhKafkaHealthIndicator(false, cfg)) {
            Health health = h.health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("mode", "broker");
        }
    }
}
