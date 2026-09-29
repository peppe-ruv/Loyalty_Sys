package io.loyaltyhub.common.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Endpoint senza controllo di ruolo, per scelta dichiarata (deny by default, F2-SEC-09, ADR-042, docs/06 §3.2).
 * Vale sul metodo o sulla classe; la dichiarazione sul metodo prevale su quella della classe.
 *
 * <p>{@link #reason()} è obbligatoria e non vuota: dice perché l'endpoint non chiede un ruolo. Un motivo vuoto vale come
 * endpoint non dichiarato: rifiutato a runtime e dalla regola ArchUnit.
 *
 * <p>Toglie solo il controllo di ruolo di {@link EndpointAccessInterceptor}: nel profilo {@code enterprise} il filtro
 * OIDC chiede comunque un token valido (liberi solo i probe di {@code /actuator}). Aprire un endpoint senza token è
 * un'altra decisione (Q-411). Un nuovo {@code @PublicEndpoint} è un caso di <em>Fermati e chiedi</em> (CLAUDE.md §7).
 */
@Documented
@EndpointAccess
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicEndpoint {

    /** Perché l'endpoint non chiede un ruolo: testo leggibile, mai vuoto. */
    String reason();
}
