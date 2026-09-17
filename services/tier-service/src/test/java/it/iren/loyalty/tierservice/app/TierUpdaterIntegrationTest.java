package it.iren.loyalty.tierservice.app;

import it.iren.loyalty.common.event.CanonicalEvents;
import it.iren.loyalty.common.event.EventTypes;
import it.iren.loyalty.common.test.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L'outbox del ledger è at-least-once (RI-08): lo stesso movimento può arrivare due volte e i punti
 * status dell'anno non devono raddoppiare, altrimenti raddoppia anche il tier.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
class TierUpdaterIntegrationTest extends PostgresIntegrationTest {

    @Autowired TierUpdater updater;
    @Autowired JdbcTemplate jdbc;

    /** Il cambio di tier pubblica: qui il broker non serve. */
    @MockitoBean KafkaTemplate<String, byte[]> kafka;

    private static byte[] movimentoStatus(String movementId, String memberId, long amount) {
        var event = CanonicalEvents.of(EventTypes.MOVEMENT_V1, "urn:iren:loyalty:ledger", "member:" + memberId,
                Map.of("movementId", movementId, "currency", "STATUS", "kind", "EARN", "amount", amount,
                        "reason", "TEST", "actionKey", "test:" + movementId + ":EARN", "balance", amount,
                        "pending", 0, "blocked", 0, "debt", false));
        return CanonicalEvents.serialize(event);
    }

    private long puntiStatus(String memberId) {
        Long v = jdbc.queryForObject("SELECT coalesce(max(status_points_year), 0) FROM tierservice.member_tier WHERE member_id = ?",
                Long.class, memberId);
        return v == null ? 0 : v;
    }

    @Test
    void loStessoMovimentoLettoDueVolteNonRaddoppiaIPuntiStatus() {
        String memberId = "m-" + UUID.randomUUID();
        byte[] movimento = movimentoStatus(UUID.randomUUID().toString(), memberId, 800);

        updater.onMovement(movimento);
        updater.onMovement(movimento);

        assertThat(puntiStatus(memberId)).isEqualTo(800);
    }

    @Test
    void movimentiDiversiSiSommano() {
        String memberId = "m-" + UUID.randomUUID();

        updater.onMovement(movimentoStatus(UUID.randomUUID().toString(), memberId, 800));
        updater.onMovement(movimentoStatus(UUID.randomUUID().toString(), memberId, 900));

        assertThat(puntiStatus(memberId)).isEqualTo(1700);
        // 1.500 punti status sono la soglia PLUS della policy di esempio: l'upgrade è immediato (RF-10).
        assertThat(jdbc.queryForObject("SELECT tier FROM tierservice.member_tier WHERE member_id = ?", String.class, memberId))
                .isEqualTo("PLUS");
    }
}
