package io.loyaltyhub.readmodel;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * Pubblica e verifica il contratto REST di read-model (RF-133): la specifica in {@code docs/contracts/} deve
 * corrispondere a quella che il servizio espone davvero.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.listener.auto-startup=false",
        "readmodel.recompute.enabled=false",
})
class ReadModelOpenApiTest extends io.loyaltyhub.common.test.OpenApiContractTest {

    @Override
    protected String serviceName() { return "read-model"; }
}
