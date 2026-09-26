package io.loyaltyhub.common.config;

import io.loyaltyhub.common.web.IdentityMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Il profilo enterprise non parte con un'identità insicura (ADR-027, ADR-044, CLAUDE.md regola 22). */
class IdentityGuardTest {

    @Test
    @DisplayName("demo senza configurazione: header X-LH-Actor come oggi")
    void demoKeepsHeader() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("demo");
        assertThat(IdentityGuard.check(env)).isEqualTo(IdentityMode.HEADER);
    }

    @Test
    @DisplayName("enterprise con l'header: rifiuto all'avvio, non un avviso")
    void enterpriseRefusesHeader() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("enterprise");
        assertThatThrownBy(() -> IdentityGuard.check(env)).hasMessageStartingWith("INSECURE_CONFIG");
        env.setProperty("loyaltyhub.identity.mode", "header");
        assertThatThrownBy(() -> IdentityGuard.check(env)).hasMessageStartingWith("INSECURE_CONFIG");
    }

    @Test
    @DisplayName("oidc senza emittente o modalità sconosciuta: rifiuto all'avvio")
    void oidcNeedsIssuer() {
        MockEnvironment env = new MockEnvironment().withProperty("loyaltyhub.identity.mode", "oidc");
        env.setActiveProfiles("enterprise");
        assertThatThrownBy(() -> IdentityGuard.check(env)).hasMessageStartingWith("INSECURE_CONFIG");
        env.setProperty("loyaltyhub.identity.issuer-uri", "https://idp.example.test/realms/loyaltyhub");
        assertThat(IdentityGuard.check(env)).isEqualTo(IdentityMode.OIDC);
        assertThatThrownBy(() -> IdentityGuard.check(new MockEnvironment().withProperty("loyaltyhub.identity.mode", "jwt")))
                .hasMessageStartingWith("INSECURE_CONFIG");
    }
}
