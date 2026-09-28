package io.loyaltyhub.insight.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Età minima dell'audit (F2-GRC-07, ADR-043, CLAUDE.md regola 22): enterprise non parte con una retention più corta. */
class AuditRetentionGuardTest {

    private static MockEnvironment env(String profile) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profile);
        return env;
    }

    @Test
    @DisplayName("enterprise con retention dell'audit sotto l'età minima: rifiuto all'avvio (INSECURE_CONFIG)")
    void enterpriseRefusesShortRetention() {
        assertThatThrownBy(() -> AuditRetentionGuard.check(env("enterprise"), AuditRetentionGuard.MIN_DAYS - 1))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("INSECURE_CONFIG");
        assertThatCode(() -> AuditRetentionGuard.check(env("enterprise"), AuditRetentionGuard.MIN_DAYS))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuditRetentionGuard.check(env("enterprise"), 400)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("demo con retention corta: parte (il database conserva comunque l'età minima)")
    void demoKeepsWorking() {
        assertThatCode(() -> AuditRetentionGuard.check(env("demo"), 1)).doesNotThrowAnyException();
    }
}
