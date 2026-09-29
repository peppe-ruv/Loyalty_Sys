package io.loyaltyhub.common.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Controllo minimo di ruolo su un endpoint (docs/06 §3). L'attore corrente
 * ({@link ActorHolder}) deve avere uno dei ruoli indicati; {@code ADMIN} passa sempre.
 * Con {@code value} vuoto vale la regola "scrittura": qualsiasi ruolo operatore tranne {@code ANALYST} e
 * {@code SOURCE} (il ruolo delle fonti di ingestion, Q-492, arriva solo dove è elencato).
 *
 * <p>È una dichiarazione di accesso ({@link EndpointAccess}): ogni endpoint porta questa annotazione oppure
 * {@link PublicEndpoint}, altrimenti è rifiutato (deny by default, F2-SEC-09). Una lettura aperta a tutti elenca tutti
 * i ruoli, {@code ANALYST} compreso (docs/08 §2: tutte le personas leggono tutto).
 */
@Documented
@EndpointAccess
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresRole {
    Role[] value() default {};
}
