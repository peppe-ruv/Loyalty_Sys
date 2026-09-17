package io.loyaltyhub.identitymapping;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * Pubblica e verifica il contratto REST di identity-mapping (RF-133): la specifica in {@code docs/contracts/} deve
 * corrispondere a quella che il servizio espone davvero.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.listener.auto-startup=false",
        "identity.merge.reconcile.enabled=false",
})
class IdentityMappingOpenApiTest extends io.loyaltyhub.common.test.OpenApiContractTest {

    @Override
    protected String serviceName() { return "identity-mapping"; }
}
