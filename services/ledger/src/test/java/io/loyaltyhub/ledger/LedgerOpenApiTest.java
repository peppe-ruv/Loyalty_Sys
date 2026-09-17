package io.loyaltyhub.ledger;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * Pubblica e verifica il contratto REST di ledger (RF-133): la specifica in {@code docs/contracts/} deve
 * corrispondere a quella che il servizio espone davvero.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.listener.auto-startup=false",
        "ledger.outbox.relay-ms=3600000",
})
class LedgerOpenApiTest extends io.loyaltyhub.common.test.OpenApiContractTest {

    @Override
    protected String serviceName() { return "ledger"; }
}
