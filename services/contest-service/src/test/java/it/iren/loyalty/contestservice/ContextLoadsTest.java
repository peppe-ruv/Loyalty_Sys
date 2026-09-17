package it.iren.loyalty.contestservice;

import it.iren.loyalty.common.test.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Il servizio si avvia davvero: contesto Spring completo su Postgres vero, con le migrazioni
 * Flyway applicate. È la prova che compilatore e test unitari non danno — bean mancanti,
 * auto-configurazioni nell'ordine sbagliato, migrazioni che non passano si vedono solo qui.
 *
 * <p>I consumer Kafka non partono: il broker non serve per provare che il contesto regge.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "ledger.outbox.relay-ms=3600000",
})
class ContextLoadsTest extends PostgresIntegrationTest {

    @Test
    void ilContestoSiAvvia() {
        // Il fallimento è l'avvio stesso: se si arriva qui, il servizio parte.
    }
}
