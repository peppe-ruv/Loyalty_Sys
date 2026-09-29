package io.loyaltyhub.common.web;

import io.loyaltyhub.common.web.MemberTestSupport.InMemoryLookup;
import io.loyaltyhub.common.web.probe.OtherModuleProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.HandlerMethod;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.loyaltyhub.common.web.MemberTestSupport.ID_A;
import static io.loyaltyhub.common.web.MemberTestSupport.ID_B;
import static io.loyaltyhub.common.web.MemberTestSupport.KEY;
import static io.loyaltyhub.common.web.MemberTestSupport.MODULE;
import static io.loyaltyhub.common.web.MemberTestSupport.SUB_A;
import static io.loyaltyhub.common.web.MemberTestSupport.TOKENS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MemberPrincipals}: risoluzione del membro dal token e dal profilo demo (Q-410, Q-550, Q-556, ADR-048): lookup
 * autorevole e non, prefisso di package più lungo con due lookup, attore e MDC {@code member:<id>}.
 */
class MemberPrincipalsTest {

    @AfterEach
    void clear() {
        ActorHolder.clear();
        MDC.clear();
    }

    private static HandlerMethod handler(Object bean, String method) throws Exception {
        return new HandlerMethod(bean, bean.getClass().getMethod(method, MemberPrincipal.class));
    }

    private static MemberEndpoint declaration(HandlerMethod handler) {
        return EndpointAccessInterceptor.resolve(handler).member();
    }

    private static MockHttpServletRequest memberRequest(String sub) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/portal/required");
        request.setAttribute(OidcActorFilter.MEMBER_TOKEN_ATTRIBUTE, new MemberTokenClaims(TOKENS.issuer(), sub));
        return request;
    }

    @Test
    @DisplayName("token legato: principal TOKEN, ActorHolder e MDC diventano member:<id>")
    void linkedTokenSetsPrincipalActorAndMdc() throws Exception {
        MemberPrincipals principals = MemberTestSupport.oidcPrincipals(MemberTestSupport.linkedLookup(false));
        HandlerMethod handler = handler(new MemberTestSupport.Portal(), "required");
        MockHttpServletRequest request = memberRequest(SUB_A);
        ActorHolder.set(ActorContext.member(null));
        assertThat(ActorHolder.get().asActorString()).isEqualTo("member:-");

        principals.bind(request, declaration(handler), handler);

        assertThat(request.getAttribute(MemberPrincipal.ATTRIBUTE)).isEqualTo(MemberPrincipal.token(ID_A));
        assertThat(ActorHolder.get().asActorString()).isEqualTo("member:" + ID_A);
        assertThat(ActorHolder.get().member()).isTrue();
        assertThat(MDC.get("actor")).isEqualTo("member:" + ID_A);
    }

    @Test
    @DisplayName("non trovato: lookup autorevole ⇒ 404 MEMBER_NOT_REGISTERED; non autorevole ⇒ 409 MEMBER_NOT_LINKED; l'attore resta member:-")
    void notFoundDependsOnAuthority() throws Exception {
        HandlerMethod handler = handler(new MemberTestSupport.Portal(), "required");
        ActorHolder.set(ActorContext.member(null));
        assertThatThrownBy(() -> MemberTestSupport.oidcPrincipals(new InMemoryLookup(MODULE, true))
                .bind(memberRequest("sconosciuto"), declaration(handler), handler))
                .isInstanceOfSatisfying(LhException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(404);
                    assertThat(e.code()).isEqualTo("MEMBER_NOT_REGISTERED");
                });
        assertThatThrownBy(() -> MemberTestSupport.oidcPrincipals(new InMemoryLookup(MODULE, false))
                .bind(memberRequest("sconosciuto"), declaration(handler), handler))
                .isInstanceOfSatisfying(LhException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(409);
                    assertThat(e.code()).isEqualTo("MEMBER_NOT_LINKED");
                });
        assertThat(ActorHolder.get().asActorString()).isEqualTo("member:-");
    }

    @Test
    @DisplayName("nell'hub vince il prefisso di package più lungo: due lookup, ciascuna per il proprio modulo")
    void longestPackagePrefixWins() throws Exception {
        InMemoryLookup broad = new InMemoryLookup("io.loyaltyhub.common", false).link(SUB_A, "BROAD");
        InMemoryLookup narrow = new InMemoryLookup("io.loyaltyhub.common.web.probe", false).link(SUB_A, "NARROW");
        MemberPrincipals principals = MemberTestSupport.oidcPrincipals(broad, narrow);

        HandlerMethod inProbe = handler(new OtherModuleProbe(), "other");
        MockHttpServletRequest first = memberRequest(SUB_A);
        principals.bind(first, declaration(inProbe), inProbe);
        assertThat(((MemberPrincipal) first.getAttribute(MemberPrincipal.ATTRIBUTE)).idOrNull()).isEqualTo("NARROW");

        HandlerMethod inCommonWeb = handler(new MemberTestSupport.Portal(), "required");
        MockHttpServletRequest second = memberRequest(SUB_A);
        principals.bind(second, declaration(inCommonWeb), inCommonWeb);
        assertThat(((MemberPrincipal) second.getAttribute(MemberPrincipal.ATTRIBUTE)).idOrNull()).isEqualTo("BROAD");

        assertThat(principals.lookupsFor(OtherModuleProbe.class)).containsExactly(narrow);
        assertThat(principals.lookupsFor(MemberTestSupport.Portal.class)).containsExactly(broad);
        assertThat(principals.lookupsFor(String.class)).isEmpty();
        // Il confine è di package, non di prefisso di testo: «io.loyaltyhub.common.we» non è il package «…common.web».
        assertThat(MemberTestSupport.oidcPrincipals(new InMemoryLookup("io.loyaltyhub.common.we", false))
                .lookupsFor(MemberTestSupport.Portal.class)).isEmpty();
    }

    @Test
    @DisplayName("nessuna lookup, due lookup con lo stesso package o chiave assente: chiude con 403 ENDPOINT_NOT_DECLARED, mai aperto")
    void misconfigurationFailsClosed() throws Exception {
        HandlerMethod handler = handler(new MemberTestSupport.Portal(), "required");
        for (MemberPrincipals principals : List.of(
                MemberTestSupport.oidcPrincipals(),
                MemberTestSupport.oidcPrincipals(MemberTestSupport.linkedLookup(false), MemberTestSupport.linkedLookup(false)),
                new MemberPrincipals(IdentityMode.OIDC, null, List.of(MemberTestSupport.linkedLookup(false))),
                new MemberPrincipals(IdentityMode.OIDC, new byte[8], List.of(MemberTestSupport.linkedLookup(false))))) {
            assertThatThrownBy(() -> principals.bind(memberRequest(SUB_A), declaration(handler), handler))
                    .isInstanceOfSatisfying(LhException.class, e -> assertThat(e.code()).isEqualTo("ENDPOINT_NOT_DECLARED"));
        }
    }

    @Test
    @DisplayName("le lookup si leggono alla prima richiesta e una sola volta")
    void lookupsAreReadLazilyOnce() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        MemberPrincipals principals = new MemberPrincipals(IdentityMode.OIDC, KEY, () -> {
            reads.incrementAndGet();
            return List.of(MemberTestSupport.linkedLookup(false));
        });
        assertThat(reads).hasValue(0);
        HandlerMethod handler = handler(new MemberTestSupport.Portal(), "required");
        principals.bind(memberRequest(SUB_A), declaration(handler), handler);
        principals.bind(memberRequest(SUB_A), declaration(handler), handler);
        assertThat(reads).hasValue(1);
    }

    @Test
    @DisplayName("la lookup riceve solo lo pseudonimo (HMAC), mai il sub né l'emittente")
    void lookupReceivesOnlyThePseudonym() throws Exception {
        InMemoryLookup spy = new InMemoryLookup(MODULE, false) {
            @Override
            public java.util.Optional<String> memberId(String subjectRef) {
                assertThat(subjectRef).matches("^[0-9a-f]{64}$").doesNotContain(SUB_A).doesNotContain("idp");
                return super.memberId(subjectRef);
            }
        }.link(SUB_A, ID_B);
        MemberPrincipals principals = MemberTestSupport.oidcPrincipals(spy);
        HandlerMethod handler = handler(new MemberTestSupport.Portal(), "required");
        MockHttpServletRequest request = memberRequest(SUB_A);
        principals.bind(request, declaration(handler), handler);
        assertThat(spy.calls).isEqualTo(1);
        assertThat(request.getAttribute(MemberPrincipal.ATTRIBUTE)).isEqualTo(MemberPrincipal.token(ID_B));
    }

    @Test
    @DisplayName("demo: il principal DEMO con l'id esplicito; l'attore e l'MDC diventano member:<id>; senza id nulla cambia")
    void demoBind() throws Exception {
        MemberPrincipals principals = MemberPrincipals.header();
        HandlerMethod handler = handler(new MemberTestSupport.Portal(), "required");

        MockHttpServletRequest withId = new MockHttpServletRequest("GET", "/v1/portal/required");
        withId.addParameter("memberId", "MBR-000003");
        principals.bind(withId, declaration(handler), handler);
        assertThat(withId.getAttribute(MemberPrincipal.ATTRIBUTE)).isEqualTo(MemberPrincipal.demo("MBR-000003"));
        assertThat(ActorHolder.get().asActorString()).isEqualTo("member:MBR-000003");
        assertThat(MDC.get("actor")).isEqualTo("member:MBR-000003");

        ActorHolder.clear();
        MDC.clear();
        MockHttpServletRequest withoutId = new MockHttpServletRequest("GET", "/v1/portal/required");
        principals.bind(withoutId, declaration(handler), handler);
        assertThat(withoutId.getAttribute(MemberPrincipal.ATTRIBUTE)).isEqualTo(MemberPrincipal.demo(null));
        assertThat(ActorHolder.get()).isEqualTo(ActorContext.ANONYMOUS);
        assertThat(MDC.get("actor")).isNull();
    }

    @Test
    @DisplayName("demo: un parametro memberId ripetuto vale il primo, come Spring; un valore vuoto resta vuoto (parità con oggi)")
    void demoParameterSemanticsMatchSpring() throws Exception {
        MemberPrincipals principals = MemberPrincipals.header();
        HandlerMethod handler = handler(new MemberTestSupport.Portal(), "required");
        MockHttpServletRequest empty = new MockHttpServletRequest("GET", "/v1/portal/required");
        empty.addParameter("memberId", "");
        principals.bind(empty, declaration(handler), handler);
        assertThat(((MemberPrincipal) empty.getAttribute(MemberPrincipal.ATTRIBUTE)).idOrNull()).isEmpty();
    }

    @Test
    @DisplayName("l'interceptor senza principals espliciti usa il profilo demo; con null vale come demo")
    void defaultInterceptorIsDemo() {
        assertThat(MemberPrincipals.header().mode()).isEqualTo(IdentityMode.HEADER);
        assertThat(new MemberPrincipals(null, null, List.of()).mode()).isEqualTo(IdentityMode.HEADER);
        assertThat(new MemberPrincipals(IdentityMode.OIDC, KEY, List.of()).hasSubjectKey()).isTrue();
        assertThat(new MemberPrincipals(IdentityMode.OIDC, new byte[31], List.of()).hasSubjectKey()).isFalse();
    }
}
