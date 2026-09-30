package io.loyaltyhub.campaign.api;

import io.loyaltyhub.testsupport.EndpointAccessRules;
import org.junit.jupiter.api.Test;

/**
 * Deny by default (F2-SEC-09, ADR-042, docs/18 §3.11): ogni endpoint dei controller di campaign-service dichiara {@code @RequiresRole},
 * {@code @PublicEndpoint} con un motivo o {@code @MemberEndpoint}; sotto {@code /v1/portal/} solo il membro dal token o una
 * lettura aperta ai membri, senza parametri legati alla richiesta (M8.10f, ADR-048).
 */
class EndpointAccessArchTest {

    @Test
    void everyEndpointDeclaresAccess() {
        EndpointAccessRules.checkPortal("io.loyaltyhub.campaign");
    }
}
