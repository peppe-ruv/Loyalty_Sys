package io.loyaltyhub.common.web;

import io.loyaltyhub.common.web.MemberTestSupport.InMemoryLookup;
import io.loyaltyhub.testsupport.OidcTestTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parametri di query con nome assente o illeggibile (Q-532 causa (a), F2-SEC-12, M8.11c): con un Tomcat vero,
 * {@code ?=x} rende inutilizzabile l'intera mappa dei parametri, e il primo {@code getParameter*}
 * ({@code @RequestParam}, {@code memberId} dell'interceptor) lancia {@code InvalidParameterException}. Doveva essere un
 * 400 del client, non un 500. MockMvc non basta: il parsing dei parametri è di Tomcat, quindi qui gira un server vero
 * (porta casuale, catena dei servizi: filtro di identità, interceptor, argument resolver, advice).
 *
 * <p>Il rifiuto è <em>fail closed</em>: un parametro senza nome non è mai ignorato in silenzio dove serve al controllo
 * del membro, e non aggira {@code MEMBER_FROM_TOKEN} (regole 6-bis, 18 e 22).
 */
class UnnamedParameterTest {

    private static AnnotationConfigServletWebServerApplicationContext demoContext;
    private static AnnotationConfigServletWebServerApplicationContext oidcContext;
    private static String demo;
    private static String oidc;

    @BeforeAll
    static void start() {
        demoContext = server(IdentityMode.HEADER);
        oidcContext = server(IdentityMode.OIDC);
        demo = "http://localhost:" + port(demoContext);
        oidc = "http://localhost:" + port(oidcContext);
    }

    @AfterAll
    static void stop() {
        for (AnnotationConfigServletWebServerApplicationContext context : List.of(demoContext, oidcContext)) {
            if (context != null) {
                context.close();
            }
        }
    }

    // ---- il difetto: forme di parametro senza nome ----

    @ParameterizedTest(name = "[demo] GET /v1/probe/search{0} → 400 bad-request")
    @ValueSource(strings = {"?=x", "?=", "?&=x", "?q=ok&=x", "?=x&q=ok", "?q=ok&=", "?&&=&"})
    @DisplayName("un parametro senza nome dove un @RequestParam legge i parametri: 400 RFC 9457, non 500")
    void unnamedParameterOnRequestParamHandlerIs400(String query) throws IOException {
        Response r = get(demo + "/v1/probe/search" + query, "ANALYST:tester", null);
        assertBadRequest(r);
    }

    @ParameterizedTest(name = "[demo] {0} → 400 bad-request")
    @ValueSource(strings = {"/v1/portal/required?=x", "/v1/portal/optional?=x", "/v1/portal/bound?=x",
            "/v1/portal/required?=&memberId=MBR-000101", "/v1/portal/required?memberId=MBR-000101&=x"})
    @DisplayName("un parametro senza nome su un @MemberEndpoint demo: 400, mai il membro risolto in silenzio")
    void unnamedParameterOnMemberEndpointDemoIs400(String target) throws IOException {
        Response r = get(demo + target, "ANALYST:tester", null);
        assertBadRequest(r);
        assertThat(r.body()).doesNotContain("\"member\"").doesNotContain("MBR-000101");
    }

    @Test
    @DisplayName("il 400 non riporta nulla di interno: né Tomcat, né l'eccezione, né la richiesta")
    void badRequestLeaksNothingInternal() throws IOException {
        Response r = get(demo + "/v1/probe/search?=secret-value", "ANALYST:tester", null);
        assertBadRequest(r);
        assertThat(r.body()).doesNotContainIgnoringCase("tomcat").doesNotContainIgnoringCase("exception")
                .doesNotContain("secret-value").doesNotContain("org.apache").doesNotContain("\tat ");
        assertThat(r.body()).contains("\"instance\":\"/v1/probe/search\"");
    }

    @Test
    @DisplayName("un parametro con codifica percentuale illeggibile è lo stesso difetto di forma: 400")
    void malformedPercentEncodingIs400() throws IOException {
        assertBadRequest(get(demo + "/v1/probe/search?q=%zz", "ANALYST:tester", null));
        assertBadRequest(get(demo + "/v1/probe/search?%zz=x", "ANALYST:tester", null));
    }

    @Test
    @DisplayName("un POST con un parametro senza nome nella query: 400")
    void unnamedParameterOnPostIs400() throws IOException {
        assertBadRequest(post(demo + "/v1/probe/submit?=x", "ADMIN:tester"));
        assertThat(post(demo + "/v1/probe/submit?reason=ok", "ADMIN:tester").status()).isEqualTo(200);
    }

    @Test
    @DisplayName("un handler che non legge i parametri non cambia: il parametro senza nome è ignorato (resta 200)")
    void handlerWithoutParametersIsUnchanged() throws IOException {
        Response r = get(demo + "/v1/probe/plain?=x", "ANALYST:tester", null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("\"ok\":true");
    }

    @Test
    @DisplayName("richieste ben formate: invariate (200 con e senza parametri)")
    void wellFormedRequestsAreUnchanged() throws IOException {
        Response r = get(demo + "/v1/probe/search?q=ciao", "ANALYST:tester", null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("\"q\":\"ciao\"");
        assertThat(get(demo + "/v1/probe/search", "ANALYST:tester", null).status()).isEqualTo(200);
        assertThat(get(demo + "/v1/probe/search?q=", "ANALYST:tester", null).status()).isEqualTo(200);
        assertThat(get(demo + "/v1/probe/search?q=a&q=b", "ANALYST:tester", null).status()).isEqualTo(200);
    }

    // ---- regressione: il controllo del membro non cambia ----

    @ParameterizedTest(name = "[oidc] token di membro + ?{0} → 400 MEMBER_FROM_TOKEN")
    @ValueSource(strings = {"memberId=MBR-000101", "MEMBERID=MBR-000101", "member_id=MBR-000101", "member-id=MBR-000101",
            "filter.memberId=MBR-000101", "items%5B0%5D.memberId=MBR-000101", "!memberId=MBR-000101", "_memberId=MBR-000101",
            "memberId=", "q=ok&memberId=MBR-000101"})
    @DisplayName("un parametro memberId (ogni grafia) su un @MemberEndpoint con token di membro resta 400 MEMBER_FROM_TOKEN")
    void memberIdParameterStillGivesMemberFromToken(String query) throws IOException {
        String token = MemberTestSupport.TOKENS.member(MemberTestSupport.SUB_A);
        Response r = get(oidc + "/v1/portal/required?" + query, null, token);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body()).contains("\"code\":\"MEMBER_FROM_TOKEN\"");
    }

    @Test
    @DisplayName("un parametro memberId su un handler non-membro con token di membro resta 400 MEMBER_FROM_TOKEN")
    void memberIdParameterOnOperatorHandlerStillGivesMemberFromToken() throws IOException {
        String token = MemberTestSupport.TOKENS.member(MemberTestSupport.SUB_A);
        Response r = get(oidc + "/v1/portal/theme?memberId=MBR-000101", null, token);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body()).contains("\"code\":\"MEMBER_FROM_TOKEN\"");
    }

    @Test
    @DisplayName("[oidc] un parametro senza nome non aggira MEMBER_FROM_TOKEN: mai 2xx, mai il membro, nessun 5xx")
    void unnamedParameterIsNeverABypassOfMemberFromToken() throws IOException {
        String token = MemberTestSupport.TOKENS.member(MemberTestSupport.SUB_A);
        for (String query : List.of("?=x&memberId=MBR-000102", "?memberId=MBR-000102&=x", "?&=&memberId=MBR-000102")) {
            Response r = get(oidc + "/v1/portal/required" + query, null, token);
            assertThat(r.status()).as(query).isEqualTo(400);
            assertThat(r.body()).as(query).doesNotContain("\"member\"").doesNotContain("MBR-0001");
            r = get(oidc + "/v1/portal/theme" + query, null, token);
            assertThat(r.status()).as(query).isEqualTo(400);
            assertThat(r.body()).as(query).doesNotContain("MBR-0001");
        }
    }

    @Test
    @DisplayName("[oidc] un parametro senza nome con un token di membro valido: 400, non il membro del token risolto in silenzio")
    void unnamedParameterWithValidMemberTokenIs400() throws IOException {
        String token = MemberTestSupport.TOKENS.member(MemberTestSupport.SUB_A);
        assertBadRequest(get(oidc + "/v1/portal/required?=x", null, token));
        assertBadRequest(get(oidc + "/v1/portal/optional?=x", null, token));
        assertBadRequest(get(oidc + "/v1/portal/theme?=x", null, token));
        // senza il difetto lo stesso token risolve il proprio membro
        Response ok = get(oidc + "/v1/portal/required", null, token);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body()).contains(MemberTestSupport.ID_A);
    }

    @Test
    @DisplayName("[demo] memberId esplicito: risolto come prima; due sorgenti diverse: 400 MEMBER_MISMATCH")
    void demoMemberIdResolutionIsUnchanged() throws IOException {
        Response r = get(demo + "/v1/portal/required?memberId=MBR-000101", "ANALYST:tester", null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("\"member\":\"MBR-000101\"");
        Response mismatch = get(demo + "/v1/portal/required?memberId=MBR-000101", "ANALYST:tester", null,
                Map.of("X-LH-Member", "MBR-000102"));
        assertThat(mismatch.status()).isEqualTo(400);
        assertThat(mismatch.body()).contains("\"code\":\"MEMBER_MISMATCH\"");
    }

    // ---- infrastruttura: un Tomcat vero con la catena dei servizi ----

    private static void assertBadRequest(Response r) {
        assertThat(r.status()).as(r.body()).isEqualTo(400);
        assertThat(r.contentType()).startsWith("application/problem+json");
        assertThat(r.body()).contains("\"type\":\"urn:loyaltyhub:problem:bad-request\"")
                .contains("\"title\":\"Richiesta non valida\"")
                .contains("\"code\":\"BAD_REQUEST\"")
                .contains("\"status\":400");
    }

    record Response(int status, String contentType, String body) {
    }

    private static Response get(String url, String actor, String bearer) throws IOException {
        return get(url, actor, bearer, Map.of());
    }

    private static Response get(String url, String actor, String bearer, Map<String, String> extra) throws IOException {
        return call("GET", url, actor, bearer, extra);
    }

    private static Response post(String url, String actor) throws IOException {
        return call("POST", url, actor, null, Map.of());
    }

    private static Response call(String method, String url, String actor, String bearer, Map<String, String> extra)
            throws IOException {
        // HttpURLConnection non riscrive la query: `?=x` e `%zz` arrivano a Tomcat come sono.
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5_000);
        c.setReadTimeout(10_000);
        if (actor != null) {
            c.setRequestProperty(ActorFilter.HEADER, actor);
        }
        if (bearer != null) {
            c.setRequestProperty("Authorization", "Bearer " + bearer);
        }
        extra.forEach(c::setRequestProperty);
        int status = c.getResponseCode();
        try (InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream()) {
            String body = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new Response(status, String.valueOf(c.getContentType()), body);
        } finally {
            c.disconnect();
        }
    }

    private static int port(AnnotationConfigServletWebServerApplicationContext context) {
        return context.getWebServer().getPort();
    }

    private static AnnotationConfigServletWebServerApplicationContext server(IdentityMode mode) {
        AnnotationConfigServletWebServerApplicationContext context = new AnnotationConfigServletWebServerApplicationContext();
        context.registerBean("tomcat", TomcatServletWebServerFactory.class, () -> new TomcatServletWebServerFactory(0));
        context.registerBean("dispatcherServlet", DispatcherServlet.class, () -> new DispatcherServlet());
        context.registerBean("probe", Probe.class, Probe::new);
        context.registerBean("portal", MemberTestSupport.Portal.class, MemberTestSupport.Portal::new);
        context.registerBean("advice", GlobalExceptionHandler.class, GlobalExceptionHandler::new);
        context.registerBean("bodyAdvice", MemberBodyAdvice.class, () -> new MemberBodyAdvice(mode));
        MemberPrincipals principals;
        if (mode == IdentityMode.OIDC) {
            InMemoryLookup lookup = MemberTestSupport.linkedLookup(false);
            principals = MemberTestSupport.oidcPrincipals(lookup);
            context.registerBean("identity", OidcActorFilter.class,
                    () -> new OidcActorFilter("probe", MemberTestSupport.decoder(), OidcTestTokens.DEFAULT_ROLES_CLAIM));
        } else {
            principals = MemberPrincipals.header();
            context.registerBean("identity", ActorFilter.class, () -> new ActorFilter("probe"));
        }
        context.registerBean("interceptor", EndpointAccessInterceptor.class, () -> new EndpointAccessInterceptor(principals));
        context.register(Mvc.class);
        context.refresh();
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class Mvc implements WebMvcConfigurer {

        private final EndpointAccessInterceptor interceptor;

        Mvc(EndpointAccessInterceptor interceptor) {
            this.interceptor = interceptor;
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(interceptor);
        }

        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(new MemberPrincipalArgumentResolver());
        }
    }

    /** Handler di operatore: uno con {@code @RequestParam} (come le 50 operazioni del difetto), uno senza parametri. */
    @RestController
    public static class Probe {

        @GetMapping("/v1/probe/search")
        @RequiresRole({Role.ADMIN, Role.MARKETING, Role.LEGAL, Role.CARE, Role.ANALYST})
        public Map<String, Object> search(@RequestParam(required = false) String q) {
            return Map.of("q", q == null ? "" : q);
        }

        @GetMapping("/v1/probe/plain")
        @RequiresRole({Role.ADMIN, Role.MARKETING, Role.LEGAL, Role.CARE, Role.ANALYST})
        public Map<String, Object> plain() {
            return Map.of("ok", true);
        }

        @PostMapping("/v1/probe/submit")
        @RequiresRole
        public Map<String, Object> submit(@RequestParam(required = false) String reason) {
            return Map.of("ok", true);
        }
    }
}
