package io.loyaltyhub.hub;

import io.loyaltyhub.testsupport.EndpointAccessRules;
import org.junit.jupiter.api.Test;

/**
 * Deny by default (F2-SEC-09, ADR-042, docs/18 §3.11): nel deployable consolidato ogni endpoint, dell'hub e di tutti i
 * moduli sul classpath, dichiara {@code @RequiresRole} o {@code @PublicEndpoint} con un motivo.
 */
class EndpointAccessArchTest {

    @Test
    void everyEndpointDeclaresAccess() {
        EndpointAccessRules.check("io.loyaltyhub");
    }
}
