package io.loyaltyhub.common.web;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.rest.fake.FakeRestController;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.i18n.SessionLocaleResolver;

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
        mvc = MockMvcBuilders.standaloneSetup(new Probe(), new OpenClass(), new AdminClass(), new Ingress())
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

    /** Ingresso di una fonte: solo SOURCE (ADMIN passa per regola). */
    @RestController
    public static class Ingress {
        @PostMapping("/v1/probe/ingress")
        @RequiresRole(Role.SOURCE)
        public Map<String, Object> ingest() {
            return Map.of("ok", true);
        }

        @PostMapping("/v1/probe/write")
        @RequiresRole
        public Map<String, Object> write() {
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
        HandlerMethod framework = new HandlerMethod(new SessionLocaleResolver(), "hashCode");
        assertThat(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), framework)).isTrue();
        assertThat(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()))
                .isTrue();
        HandlerMethod ours = new HandlerMethod(new Probe(), "undeclared", String.class);
        assertThatThrownBy(() -> interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), ours))
                .isInstanceOfSatisfying(LhException.class, e -> assertThat(e.code()).isEqualTo("ENDPOINT_NOT_DECLARED"));
    }

    @Test
    void onlyTheNarrowFrameworkPackagesAreExempt() {
        assertThat(EndpointAccessInterceptor.isFramework(SessionLocaleResolver.class)).isTrue();
        assertThat(EndpointAccessInterceptor.isFramework(org.springframework.util.AntPathMatcher.class)).isFalse();
        assertThat(EndpointAccessInterceptor.isFramework(FakeRestController.class)).isFalse();
    }

    @Test
    void controllerInAnotherSpringPackageIsDeniedWhenUndeclared() throws Exception {
        EndpointAccessInterceptor interceptor = new EndpointAccessInterceptor();
        HandlerMethod fake = new HandlerMethod(new FakeRestController(), "undeclared");
        assertThatThrownBy(() -> interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), fake))
                .isInstanceOfSatisfying(LhException.class, e -> assertThat(e.code()).isEqualTo("ENDPOINT_NOT_DECLARED"));
    }

    @Test
    void asyncDispatchIsNotCheckedAgain() throws Exception {
        EndpointAccessInterceptor interceptor = new EndpointAccessInterceptor();
        HandlerMethod undeclared = new HandlerMethod(new Probe(), "undeclared", String.class);
        MockHttpServletRequest async = new MockHttpServletRequest();
        async.setDispatcherType(DispatcherType.ASYNC);
        assertThat(interceptor.preHandle(async, new MockHttpServletResponse(), undeclared)).isTrue();
        // La richiesta iniziale (REQUEST) resta rifiutata.
        MockHttpServletRequest initial = new MockHttpServletRequest();
        assertThat(initial.getDispatcherType()).isEqualTo(DispatcherType.REQUEST);
        assertThatThrownBy(() -> interceptor.preHandle(initial, new MockHttpServletResponse(), undeclared))
                .isInstanceOf(LhException.class);
    }

    @Test
    void resolveReportsTheEffectiveDeclaration() throws Exception {
        assertThat(EndpointAccessInterceptor.resolve(new HandlerMethod(new Probe(), "undeclared", String.class)).valid())
                .isFalse();
        assertThat(EndpointAccessInterceptor.resolve(new HandlerMethod(new Probe(), "blank")).valid()).isFalse();
        assertThat(EndpointAccessInterceptor.resolve(new HandlerMethod(new Probe(), "open")).valid()).isTrue();
        assertThat(EndpointAccessInterceptor.resolve(new HandlerMethod(new Probe(), "read")).role()).isNotNull();
        assertThat(EndpointAccessInterceptor.resolve(new HandlerMethod(new OpenClass(), "inherited")).valid()).isTrue();
    }

    @Test
    void resolveReportsTheMemberDeclarationAndItsConflicts() throws Exception {
        // La terza dichiarazione (Q-410, ADR-048): valida da sola, mai insieme a @RequiresRole o @PublicEndpoint.
        HandlerMethod member = new HandlerMethod(new MemberTestSupport.Portal(),
                MemberTestSupport.Portal.class.getMethod("required", MemberPrincipal.class));
        EndpointAccessInterceptor.Declaration declaration = EndpointAccessInterceptor.resolve(member);
        assertThat(declaration.member()).isNotNull();
        assertThat(declaration.member().value()).isEqualTo(MemberEndpoint.Mode.REQUIRED);
        assertThat(declaration.role()).isNull();
        assertThat(declaration.valid()).isTrue();
        assertThat(declaration.conflicting()).isFalse();

        HandlerMethod combined = new HandlerMethod(new MemberTestSupport.Portal(),
                MemberTestSupport.Portal.class.getMethod("combined", MemberPrincipal.class));
        assertThat(EndpointAccessInterceptor.resolve(combined).conflicting()).isTrue();
        assertThat(EndpointAccessInterceptor.resolve(combined).valid()).isFalse();

        HandlerMethod membersRead = new HandlerMethod(new MemberTestSupport.Portal(),
                MemberTestSupport.Portal.class.getMethod("theme"));
        assertThat(EndpointAccessInterceptor.resolve(membersRead).role().members()).isTrue();
        // Il vecchio costruttore a due componenti resta valido e senza il membro.
        assertThat(new EndpointAccessInterceptor.Declaration(null, null).valid()).isFalse();
        assertThat(new EndpointAccessInterceptor.Declaration(null, null).member()).isNull();
    }

    @Test
    void sourceReachesOnlyWhatListsItAndNeverTheWriteRuleOrTheReadLists() throws Exception {
        String source = "SOURCE:src-crm";
        // Ingresso della fonte: SOURCE e ADMIN passano, gli altri (anche senza attore) no.
        mvc.perform(post("/v1/probe/ingress").header(ActorFilter.HEADER, source)).andExpect(status().isOk());
        mvc.perform(post("/v1/probe/ingress").header(ActorFilter.HEADER, "ADMIN:marta.admin")).andExpect(status().isOk());
        for (String other : new String[] {null, "ANALYST:sara.analyst", "MARKETING:luca.marketing", "CARE:paolo.care"}) {
            var req = post("/v1/probe/ingress");
            mvc.perform(other == null ? req : req.header(ActorFilter.HEADER, other)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        }
        // @RequiresRole vuoto (regola «scrittura»): mai SOURCE, come mai ANALYST.
        mvc.perform(post("/v1/probe/write").header(ActorFilter.HEADER, source)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        mvc.perform(post("/v1/probe/write").header(ActorFilter.HEADER, "CARE:paolo.care")).andExpect(status().isOk());
        mvc.perform(post("/v1/probe/write").header(ActorFilter.HEADER, "ADMIN:marta.admin")).andExpect(status().isOk());
        // Lettura a tutti i ruoli (elenco dei cinque): SOURCE non è tra i cinque.
        mvc.perform(get("/v1/probe/read").header(ActorFilter.HEADER, source)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        // Ruoli di ruolo singolo, di classe e ADMIN-only: SOURCE no.
        mvc.perform(post("/v1/probe/care").header(ActorFilter.HEADER, source)).andExpect(status().isForbidden());
        mvc.perform(get("/v1/probe/admin-class").header(ActorFilter.HEADER, source)).andExpect(status().isForbidden());
        // Endpoint non dichiarato: 403 ENDPOINT_NOT_DECLARED per SOURCE come per tutti.
        mvc.perform(get("/v1/probe/undeclared/x").header(ActorFilter.HEADER, source)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_DECLARED"));
        // Un endpoint pubblico resta pubblico.
        mvc.perform(get("/v1/probe/public").header(ActorFilter.HEADER, source)).andExpect(status().isOk());
    }
}
