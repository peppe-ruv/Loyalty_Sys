package io.loyaltyhub.gamification.api;

import io.loyaltyhub.testsupport.EndpointAccessRules;
import org.junit.jupiter.api.Test;

/**
 * Deny by default (F2-SEC-09, ADR-042, docs/18 §3.11): ogni endpoint dei controller di gamification-service dichiara {@code @RequiresRole} o
 * {@code @PublicEndpoint} con un motivo, oppure {@code @MemberEndpoint}; i controller del portale (M8.10f, ADR-048) portano il
 * membro solo dal token ({@code checkPortal}).
 */
class EndpointAccessArchTest {

    @Test
    void everyEndpointDeclaresAccess() {
        EndpointAccessRules.checkPortal("io.loyaltyhub.gamification");
    }
}
