package io.loyaltyhub.common.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Un handler (o un controller) che risponde da sé a un carattere NUL ({@code U+0000}) nel corpo JSON: il rifiuto generico
 * con {@code 400} di {@link NulRejectingModule} non si applica al suo corpo (Q-532 causa (3), F2-SEC-12). Vale per chi ha
 * una regola documentata e verificata sui NUL, per esempio l'ingresso eventi di {@code ingestion-service}, dove il batch dà
 * un esito {@code INVALID} per elemento e un evento singolo {@code 400} col nome del campo (Q-371,
 * {@code EnvelopeLimits}): senza l'eccezione il batch intero sarebbe rifiutato prima di arrivare al servizio.
 *
 * <p>Non toglie il controllo su percorso, query e parametri ({@link NulRejectingFilter}): il NUL non è mai ammesso lì.
 * Chi usa questa annotazione si assume che nessun NUL arrivi al database: è una scelta da dichiarare con ADR o Q.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface NulTolerantBody {
}
