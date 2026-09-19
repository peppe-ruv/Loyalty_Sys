package io.loyaltyhub.common.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Controllo minimo di ruolo su un endpoint (docs/06 §3). L'attore corrente
 * ({@link ActorHolder}) deve avere uno dei ruoli indicati; {@code ADMIN} passa sempre.
 * Con {@code value} vuoto vale la regola "scrittura": qualsiasi ruolo tranne {@code ANALYST}.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresRole {
    Role[] value() default {};
}
