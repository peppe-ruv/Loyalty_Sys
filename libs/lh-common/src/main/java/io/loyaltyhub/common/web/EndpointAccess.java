package io.loyaltyhub.common.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Segna un'annotazione come <em>dichiarazione di accesso</em> di un endpoint (deny by default, F2-SEC-09, ADR-042,
 * CLAUDE.md regola 18). Oggi la portano {@link RequiresRole} e {@link PublicEndpoint}.
 *
 * <p>È una marcatura documentale: <strong>non</strong> è ciò che decide l'accesso. {@link EndpointAccessInterceptor}
 * e la regola ArchUnit di {@code lh-test-support} ({@code EndpointAccessRules}) applicano un elenco esplicito
 * ({@link RequiresRole}, {@link PublicEndpoint} con motivo), identico nei due posti: un'annotazione segnata solo
 * {@code @EndpointAccess} non è accettata. Una nuova dichiarazione (per esempio quella del membro dal token,
 * {@code MemberPrincipal}, Q-410) si aggiunge all'interceptor <strong>e</strong> alla regola nello stesso
 * cambiamento; finché l'interceptor non la conosce, l'endpoint resta rifiutato ({@code ENDPOINT_NOT_DECLARED}),
 * mai aperto.
 */
@Documented
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EndpointAccess {
}
