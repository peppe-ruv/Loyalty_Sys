package io.loyaltyhub.common.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Segna un'annotazione come <em>dichiarazione di accesso</em> di un endpoint (deny by default, F2-SEC-09, ADR-042,
 * CLAUDE.md regola 18). Oggi la portano {@link RequiresRole} e {@link PublicEndpoint}; la regola ArchUnit di
 * {@code lh-test-support} ({@code EndpointAccessRules}) accetta un metodo di controller che porta, sul metodo o sulla
 * classe, un'annotazione segnata così.
 *
 * <p>Una nuova dichiarazione (per esempio quella del membro dal token, {@code MemberPrincipal}, Q-410) si segna con
 * questa annotazione <strong>e</strong> si applica in {@link EndpointAccessInterceptor}: finché l'interceptor non la
 * conosce, l'endpoint resta rifiutato ({@code ENDPOINT_NOT_DECLARED}), mai aperto.
 */
@Documented
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EndpointAccess {
}
