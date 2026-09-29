package io.loyaltyhub.common.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deny by default in {@link EndpointAccessInterceptor} (F2-SEC-09, ADR-042, docs/06 §3.2): endpoint non dichiarato
 * ⇒ {@code 403 ENDPOINT_NOT_DECLARED} per chiunque, ADMIN compreso; {@link PublicEndpoint} con motivo ⇒ passa senza
 * attore; {@link RequiresRole} invariata; dichiarazione del metodo prima di quella della classe.
 */
@ExtendWith(OutputCaptureExtension.class)
class EndpointAccessInterceptorTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new Probe(), new OpenClass(), new AdminClass())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new ActorFilter("probe"))
                .addInterceptors(new EndpointAccessInterceptor())
                .build();
    }

    @AfterEach
    void clear() {
        ActorHolder.clear();
    }

    @RestController
    public static class Probe {
        @GetMapping("/v1/probe/undeclared/{memberId}")
        public Map<String, Object> undeclared(@PathVariable String memberId) {
            return Map.of("memberId", memberId);
        }

        @GetMapping("/v1/probe/public")
        @PublicEndpoint(reason = "sonda del test")
        public Map<String, Object> open() {
            return Map.of("ok", true);
        }

        @GetMapping("/v1/probe/blank")
        @PublicEndpoint(reason = "   ")
        public Map<String, Object> blank() {
            return Map.of("ok", true);
        }

        @PostMapping("/v1/probe/care")
        @RequiresRole({Role.ADMIN, Role.CARE})
        public Map<String, Object> care() {
            return Map.of("ok", true);
        }

        @GetMapping("/v1/probe/read")
        @RequiresRole({Role.ADMIN, Role.MARKETING, Role.LEGAL, Role.CARE, Role.ANALYST})
        public Map<String, Object> read() {
            return Map.of("ok", true);
        }

        @GetMapping("/v1/probe/both")
        @RequiresRole(Role.ADMIN)
        @PublicEndpoint(reason = "ambiguo: vince il ruolo")
        public Map<String, Object> both() {
            return Map.of("ok", true);
        }
    }

    /** Classe pubblica con un metodo che chiede un ruolo: la dichiarazione del metodo prevale. */
    @RestController
    @PublicEndpoint(reason = "sonda di classe")
    public static class OpenClass {
        @GetMapping("/v1/probe/open-class")
        public Map<String, Object> inherited() {
            return Map.of("ok", true);
        }

        @PostMapping("/v1/probe/open-class/admin")
        @RequiresRole(Role.ADMIN)
        public Map<String, Object> admin() {
            return Map.of("ok", true);
        }
    }

    /** Classe ADMIN con un metodo pubblico: la dichiarazione del metodo prevale. */
    @RestController
    @RequiresRole(Role.ADMIN)
    public static class AdminClass {
        @GetMapping("/v1/probe/admin-class")
        public Map<String, Object> inherited() {
            return Map.of("ok", true);
        }

        @GetMapping("/v1/probe/admin-class/public")
        @PublicEndpoint(reason = "eccezione dichiarata")
        public Map<String, Object> open() {
            return Map.of("ok", true);
        }
    }

    @Test
    void undeclaredEndpointIsForbiddenToEveryoneEvenAdmin() throws Exception {
        for (String actor : new String[] {null, "ANALYST:sara.analyst", "CARE:paolo.care", "ADMIN:marta.admin"}) {
            var req = get("/v1/probe/undeclared/MBR-000003");
            if (actor != null) {
                req = req.header(ActorFilter.HEADER, actor);
            }
            mvc.perform(req)
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.type").value("urn:loyaltyhub:problem:endpoint-not-declared"))
                    .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_DECLARED"))
                    .andExpect(jsonPath("$.title").value("Operazione non consentita"))
                    .andExpect(jsonPath("$.status").value(403));
        }
    }

    @Test
    void refusalIsLoggedWithoutPathOrActor(CapturedOutput output) throws Exception {
        mvc.perform(get("/v1/probe/undeclared/MBR-000003").header(ActorFilter.HEADER, "CARE:paolo.care"))
                .andExpect(status().isForbidden());
        assertThat(output.getOut() + output.getErr())
                .contains("deny by default")
                .contains(Probe.class.getName() + "#undeclared")
                .doesNotContain("MBR-000003")
                .doesNotContain("paolo.care");
    }

    @Test
    void publicEndpointPassesWithoutActor() throws Exception {
        mvc.perform(get("/v1/probe/public")).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        mvc.perform(get("/v1/probe/open-class")).andExpect(status().isOk());
    }

    @Test
    void publicEndpointWithBlankReasonIsUndeclared() throws Exception {
        mvc.perform(get("/v1/probe/blank").header(ActorFilter.HEADER, "ADMIN:marta.admin"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_DECLARED"));
    }

    @Test
    void requiresRoleIsUnchanged() throws Exception {
        mvc.perform(post("/v1/probe/care").header(ActorFilter.HEADER, "MARKETING:luca.marketing"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        mvc.perform(post("/v1/probe/care")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        mvc.perform(post("/v1/probe/care").header(ActorFilter.HEADER, "CARE:paolo.care")).andExpect(status().isOk());
        mvc.perform(post("/v1/probe/care").header(ActorFilter.HEADER, "ADMIN:marta.admin")).andExpect(status().isOk());
    }

    @Test
    void readOpenToEveryRoleKeepsTheAnonymousDemoAnalyst() throws Exception {
        // Profilo demo: senza X-LH-Actor l'attore è ANALYST:anonymous e la lettura resta aperta (docs/08 §2).
        mvc.perform(get("/v1/probe/read")).andExpect(status().isOk());
        mvc.perform(get("/v1/probe/read").header(ActorFilter.HEADER, "LEGAL:elena.legal")).andExpect(status().isOk());
    }

    @Test
    void roleWinsWhenBothAreOnTheSameElement() throws Exception {
        mvc.perform(get("/v1/probe/both")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        mvc.perform(get("/v1/probe/both").header(ActorFilter.HEADER, "ADMIN:marta.admin")).andExpect(status().isOk());
    }

    @Test
    void methodDeclarationWinsOverClassDeclaration() throws Exception {
        mvc.perform(post("/v1/probe/open-class/admin")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        mvc.perform(get("/v1/probe/admin-class")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        mvc.perform(get("/v1/probe/admin-class").header(ActorFilter.HEADER, "ADMIN:marta.admin"))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/probe/admin-class/public")).andExpect(status().isOk());
    }

    @Test
    void frameworkHandlersAndNonMethodHandlersAreOutOfScope() throws Exception {
        EndpointAccessInterceptor interceptor = new EndpointAccessInterceptor();
        HandlerMethod framework = new HandlerMethod(new AntPathMatcher(), "isPattern", String.class);
        assertThat(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), framework)).isTrue();
        assertThat(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()))
                .isTrue();
        HandlerMethod ours = new HandlerMethod(new Probe(), "undeclared", String.class);
        assertThatThrownBy(() -> interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), ours))
                .isInstanceOfSatisfying(LhException.class, e -> assertThat(e.code()).isEqualTo("ENDPOINT_NOT_DECLARED"));
    }
}
