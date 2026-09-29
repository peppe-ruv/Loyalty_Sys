package io.loyaltyhub.common.web;

import io.loyaltyhub.testsupport.EndpointAccessRules;
import org.junit.jupiter.api.Test;

/**
 * Deny by default (F2-SEC-09, ADR-042, docs/18 §3.11): ogni endpoint dei controller di lh-common (demo, approvazioni) dichiara {@code @RequiresRole} o
 * {@code @PublicEndpoint} con un motivo.
 */
class EndpointAccessArchTest {

    @Test
    void everyEndpointDeclaresAccess() {
        EndpointAccessRules.check("io.loyaltyhub.common");
    }
}
