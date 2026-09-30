package io.loyaltyhub.member.api;

import io.loyaltyhub.testsupport.EndpointAccessRules;
import org.junit.jupiter.api.Test;

/**
 * Deny by default (F2-SEC-09, ADR-042, docs/18 §3.11): ogni endpoint dei controller di member-service dichiara {@code @RequiresRole}, {@code @MemberEndpoint} o
 * {@code @PublicEndpoint} con un motivo.
 */
class EndpointAccessArchTest {

    /**
     * Anche la regola del portale (Q-410, ADR-048): ogni handler sotto {@code /v1/portal/} è {@code @MemberEndpoint} (il
     * membro viene solo dal token) o una lettura {@code members = true} senza parametri della richiesta.
     */
    @Test
    void everyEndpointDeclaresAccess() {
        EndpointAccessRules.checkPortal("io.loyaltyhub.member");
    }
}
