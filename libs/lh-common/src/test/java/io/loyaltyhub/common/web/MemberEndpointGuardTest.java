package io.loyaltyhub.common.web;

import io.loyaltyhub.common.web.MemberTestSupport.InMemoryLookup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.mock.web.MockServletContext;

import java.util.List;
import java.util.Map;

import static io.loyaltyhub.common.web.MemberTestSupport.KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MemberEndpointGuard}: all'avvio gli endpoint del membro sono configurati in modo sicuro o l'avvio fallisce con
 * {@code INSECURE_CONFIG} (regola 22): sotto {@code /v1/portal/}, {@code demoPathVariable} solo su handler deprecati e
 * presente nel percorso, {@code members = true} solo su GET fuori da {@code /me}; in {@code oidc} lookup e chiave.
 */
class MemberEndpointGuardTest {

    @RestController
    @RequestMapping("/v1/portal")
    static class Good {
        @GetMapping("/wallet")
        @MemberEndpoint
        public Map<String, Object> wallet(MemberPrincipal p) {
            return Map.of();
        }

        @GetMapping("/legacy/{memberId}")
        @Deprecated
        @MemberEndpoint(demoPathVariable = "memberId")
        public Map<String, Object> legacy(MemberPrincipal p) {
            return Map.of();
        }

        @GetMapping("/tiers")
        @RequiresRole(value = {Role.ADMIN, Role.ANALYST}, members = true)
        public Map<String, Object> tiers() {
            return Map.of();
        }

        @PostMapping("/members")
        @MemberEndpoint(MemberEndpoint.Mode.REGISTRATION)
        public Map<String, Object> register(MemberSubject s) {
            return Map.of();
        }
    }

    @RestController
    static class OutsidePortal {
        @GetMapping("/v1/wallets/me")
        @MemberEndpoint
        public Map<String, Object> outside(MemberPrincipal p) {
            return Map.of();
        }
    }

    @RestController
    static class PathVariableNotDeprecated {
        @GetMapping("/v1/portal/legacy/{memberId}")
        @MemberEndpoint(demoPathVariable = "memberId")
        public Map<String, Object> legacy(MemberPrincipal p) {
            return Map.of();
        }
    }

    @RestController
    static class PathVariableMissing {
        @GetMapping("/v1/portal/legacy/{id}")
        @Deprecated
        @MemberEndpoint(demoPathVariable = "memberId")
        public Map<String, Object> legacy(MemberPrincipal p) {
            return Map.of();
        }
    }

    @RestController
    static class MembersReadOnPost {
        @PostMapping("/v1/portal/theme")
        @RequiresRole(value = {Role.ADMIN, Role.ANALYST}, members = true)
        public Map<String, Object> theme() {
            return Map.of();
        }
    }

    @RestController
    static class MembersReadOutsidePortal {
        @GetMapping("/v1/theme")
        @RequiresRole(value = {Role.ADMIN, Role.ANALYST}, members = true)
        public Map<String, Object> theme() {
            return Map.of();
        }
    }

    @RestController
    static class MembersReadUnderMe {
        @GetMapping("/v1/portal/me/wallet")
        @RequiresRole(value = {Role.ADMIN, Role.ANALYST}, members = true)
        public Map<String, Object> wallet() {
            return Map.of();
        }
    }

    @RestController
    static class MembersReadAnyVerb {
        @RequestMapping("/v1/portal/anything")
        @RequiresRole(value = {Role.ADMIN, Role.ANALYST}, members = true)
        public Map<String, Object> anything() {
            return Map.of();
        }
    }

    @Configuration
    @EnableWebMvc
    static class Base {
    }

    private static MemberEndpointGuard guard(MemberPrincipals principals, MockEnvironment env, Class<?>... controllers) {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Base.class);
        context.register(controllers);
        context.refresh();
        return new MemberEndpointGuard(context.getBeanProvider(
                org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class), principals, env);
    }

    private static MockEnvironment oidcEnv(String subjectKeyBase64) {
        MockEnvironment env = new MockEnvironment().withProperty("loyaltyhub.identity.mode", "oidc")
                .withProperty("loyaltyhub.identity.issuer-uri", "https://idp.example.test/realms/loyaltyhub");
        if (subjectKeyBase64 != null) {
            env.setProperty("loyaltyhub.identity.subject-key", subjectKeyBase64);
        }
        return env;
    }

    private static void assertInsecure(MemberEndpointGuard guard, String... fragments) {
        assertThatThrownBy(guard::verify).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("INSECURE_CONFIG").satisfies(e -> {
                    for (String fragment : fragments) {
                        assertThat(e.getMessage()).contains(fragment);
                    }
                });
    }

    private static InMemoryLookup lookup(boolean authoritative) {
        return new InMemoryLookup("io.loyaltyhub.common.web", authoritative);
    }

    @Test
    @DisplayName("demo: una configurazione corretta passa senza chiave né lookup")
    void demoGoodConfigurationPasses() {
        MemberEndpointGuard guard = guard(MemberPrincipals.header(), new MockEnvironment(), Good.class);
        assertThatCode(guard::verify).doesNotThrowAnyException();
        assertThatCode(guard::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("un @MemberEndpoint fuori da /v1/portal/ ⇒ INSECURE_CONFIG")
    void memberEndpointOutsideThePortal() {
        assertInsecure(guard(MemberPrincipals.header(), new MockEnvironment(), OutsidePortal.class),
                "OutsidePortal#outside", "solo sotto /v1/portal/");
    }

    @Test
    @DisplayName("demoPathVariable su un handler non deprecato, o assente dal percorso ⇒ INSECURE_CONFIG")
    void demoPathVariableRules() {
        assertInsecure(guard(MemberPrincipals.header(), new MockEnvironment(), PathVariableNotDeprecated.class),
                "demoPathVariable solo su un handler @Deprecated");
        assertInsecure(guard(MemberPrincipals.header(), new MockEnvironment(), PathVariableMissing.class),
                "non compare nel percorso");
    }

    @Test
    @DisplayName("members = true: solo GET, solo sotto /v1/portal/, mai sotto /me ⇒ altrimenti INSECURE_CONFIG")
    void membersReadRules() {
        assertInsecure(guard(MemberPrincipals.header(), new MockEnvironment(), MembersReadOnPost.class), "solo su GET");
        assertInsecure(guard(MemberPrincipals.header(), new MockEnvironment(), MembersReadOutsidePortal.class),
                "solo sotto /v1/portal/");
        assertInsecure(guard(MemberPrincipals.header(), new MockEnvironment(), MembersReadUnderMe.class),
                "mai sotto /v1/portal/me");
        assertInsecure(guard(MemberPrincipals.header(), new MockEnvironment(), MembersReadAnyVerb.class), "solo su GET");
    }

    @Test
    @DisplayName("oidc: la configurazione completa (lookup autorevole, chiave da 32 byte) passa")
    void oidcGoodConfigurationPasses() {
        MemberPrincipals principals = new MemberPrincipals(IdentityMode.OIDC, KEY, List.of(lookup(true)));
        assertThatCode(guard(principals, oidcEnv(java.util.Base64.getEncoder().encodeToString(KEY)), Good.class)::verify)
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("oidc: nessuna lookup per il modulo, due lookup, o registrazione senza lookup autorevole ⇒ INSECURE_CONFIG")
    void oidcLookupRules() {
        String key = java.util.Base64.getEncoder().encodeToString(KEY);
        assertInsecure(guard(new MemberPrincipals(IdentityMode.OIDC, KEY, List.of()), oidcEnv(key), Good.class),
                "esattamente una MemberSubjectLookup", "trovate 0");
        assertInsecure(guard(new MemberPrincipals(IdentityMode.OIDC, KEY, List.of(lookup(true), lookup(true))),
                oidcEnv(key), Good.class), "trovate 2");
        assertInsecure(guard(new MemberPrincipals(IdentityMode.OIDC, KEY, List.of(lookup(false))), oidcEnv(key), Good.class),
                "la registrazione richiede la lookup autorevole");
    }

    @Test
    @DisplayName("oidc: chiave assente ⇒ INSECURE_CONFIG (senza il valore); senza @MemberEndpoint la chiave non serve")
    void oidcKeyRules() {
        MemberPrincipals noKey = new MemberPrincipals(IdentityMode.OIDC, null, List.of(lookup(true)));
        assertInsecure(guard(noKey, oidcEnv(null), Good.class), "LH_SUBJECT_KEY");
        // Nessun endpoint del membro (l'hub di oggi in enterprise): non serve la chiave né la lookup.
        assertThatCode(guard(noKey, oidcEnv(null), NoMemberEndpoints.class)::verify).doesNotThrowAnyException();
    }

    @RestController
    static class NoMemberEndpoints {
        @GetMapping("/v1/portal/plain")
        @RequiresRole({Role.ADMIN, Role.ANALYST})
        public Map<String, Object> plain() {
            return Map.of();
        }
    }

    @Test
    @DisplayName("i controller dei framework e un contesto senza mappature non sono un problema")
    void emptyContextIsFine() {
        try (AnnotationConfigApplicationContext empty = new AnnotationConfigApplicationContext()) {
            empty.refresh();
            assertThatCode(new MemberEndpointGuard(
                    empty.getBeanProvider(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class),
                    MemberPrincipals.header(), new MockEnvironment())::verify).doesNotThrowAnyException();
        }
    }
}
