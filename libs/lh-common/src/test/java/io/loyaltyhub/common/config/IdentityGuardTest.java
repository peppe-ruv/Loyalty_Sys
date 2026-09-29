package io.loyaltyhub.common.config;

import io.loyaltyhub.common.web.IdentityMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.loyaltyhub.common.identity.SubjectRef;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;
import java.util.Map;

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

    private static String b64(int bytes) {
        byte[] key = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            key[i] = (byte) (i + 1);
        }
        return Base64.getEncoder().encodeToString(key);
    }

    @Test
    @DisplayName("[Q-552] chiave dello pseudonimo: assente = null; base64 di almeno 32 byte accettata (anche URL-safe)")
    void subjectKeyIsOptionalButChecked() {
        MockEnvironment env = new MockEnvironment();
        assertThat(IdentityGuard.subjectKey(env)).isNull();
        env.setProperty(IdentityGuard.SUBJECT_KEY_PROPERTY, "   ");
        assertThat(IdentityGuard.subjectKey(env)).isNull();
        env.setProperty(IdentityGuard.SUBJECT_KEY_PROPERTY, b64(32));
        assertThat(IdentityGuard.subjectKey(env)).hasSize(32);
        env.setProperty(IdentityGuard.SUBJECT_KEY_PROPERTY, b64(64));
        assertThat(IdentityGuard.subjectKey(env)).hasSize(64);
        byte[] urlSafe = new byte[33];
        java.util.Arrays.fill(urlSafe, (byte) 0xfb); // in base64 standard contiene «+» e «/»
        env.setProperty(IdentityGuard.SUBJECT_KEY_PROPERTY, Base64.getUrlEncoder().encodeToString(urlSafe));
        assertThat(IdentityGuard.subjectKey(env)).hasSize(33);
    }

    @Test
    @DisplayName("[Q-552, regola 22] chiave corta o non base64: INSECURE_CONFIG, e il messaggio non riporta mai il valore (regola 20)")
    void subjectKeyMustBeStrong() {
        MockEnvironment env = new MockEnvironment();
        String shortKey = b64(31);
        env.setProperty(IdentityGuard.SUBJECT_KEY_PROPERTY, shortKey);
        assertThatThrownBy(() -> IdentityGuard.subjectKey(env)).hasMessageStartingWith("INSECURE_CONFIG")
                .hasMessageNotContaining(shortKey);
        env.setProperty(IdentityGuard.SUBJECT_KEY_PROPERTY, "questa non è base64!!");
        assertThatThrownBy(() -> IdentityGuard.subjectKey(env)).hasMessageStartingWith("INSECURE_CONFIG")
                .hasMessageNotContaining("questa non");
        assertThat(SubjectRef.MIN_KEY_BYTES).isEqualTo(32);
    }

    @Test
    @DisplayName("[Q-552] requireSubjectKey: chiave assente ⇒ INSECURE_CONFIG (oidc con gli endpoint del membro); presente e forte ⇒ i byte")
    void subjectKeyRequiredForMemberEndpoints() {
        MockEnvironment env = new MockEnvironment();
        assertThatThrownBy(() -> IdentityGuard.requireSubjectKey(env)).hasMessageStartingWith("INSECURE_CONFIG")
                .hasMessageContaining("LH_SUBJECT_KEY");
        env.setProperty(IdentityGuard.SUBJECT_KEY_PROPERTY, b64(32));
        assertThat(IdentityGuard.requireSubjectKey(env)).hasSize(32);
    }

    @Test
    @DisplayName("[Q-552] la variabile LH_SUBJECT_KEY diventa loyaltyhub.identity.subject-key")
    void subjectKeyAlias() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("LH_SUBJECT_KEY", b64(32))));
        new LhEnvironmentAliases().postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getProperty("loyaltyhub.identity.subject-key")).isEqualTo(b64(32));
        assertThat(IdentityGuard.subjectKey(env)).hasSize(32);
    }
}
