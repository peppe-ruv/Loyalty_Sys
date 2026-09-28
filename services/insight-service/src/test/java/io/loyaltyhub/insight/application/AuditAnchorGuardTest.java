package io.loyaltyhub.insight.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ancoraggio dell'audit sempre attivo in enterprise (F2-GRC-07, CLAUDE.md regole 22 e 24). */
class AuditAnchorGuardTest {

    private static MockEnvironment env(String profile) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profile);
        return env;
    }

    @Test
    @DisplayName("enterprise con il job di ancoraggio spento o il logger delle ancore sotto INFO: rifiuto all'avvio")
    void enterpriseRefusesAnchoringOff() {
        assertThatThrownBy(() -> AuditAnchorGuard.check(env("enterprise"), "-", true))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("INSECURE_CONFIG").hasMessageContaining("anchor-cron");
        assertThatThrownBy(() -> AuditAnchorGuard.check(env("enterprise"), " ", true))
                .hasMessageStartingWith("INSECURE_CONFIG");
        assertThatThrownBy(() -> AuditAnchorGuard.check(env("enterprise"), "0 5 0 * * *", false))
                .hasMessageStartingWith("INSECURE_CONFIG").hasMessageContaining(AuditAnchorLog.LOGGER);
        assertThatCode(() -> AuditAnchorGuard.check(env("enterprise"), "0 5 0 * * *", true)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("demo e test con l'ancoraggio spento: partono (avviso)")
    void demoKeepsWorking() {
        assertThatCode(() -> AuditAnchorGuard.check(env("demo"), "-", false)).doesNotThrowAnyException();
    }
}
