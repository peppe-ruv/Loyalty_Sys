package io.loyaltyhub.common.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Terza dichiarazione di accesso, accanto a {@link RequiresRole} e {@link PublicEndpoint} (F2-SEC-09, ADR-042,
 * ADR-048, Q-410): l'handler serve <em>il membro del token</em>, mai un membro indicato dal chiamante.
 * {@link EndpointAccessInterceptor} risolve il membro e passa al controller un {@link MemberPrincipal} (modi
 * {@link Mode#REQUIRED} e {@link Mode#OPTIONAL}) o un {@link MemberSubject} ({@link Mode#REGISTRATION}); il controller
 * non legge {@code memberId} da query, corpo, percorso o header.
 *
 * <p>Vale sul metodo o sulla classe (il metodo prevale) ed è alternativa a {@link RequiresRole} e
 * {@link PublicEndpoint} sullo stesso elemento: la combinazione è rifiutata a runtime
 * ({@code 403 ENDPOINT_NOT_DECLARED}) e dalla regola ArchUnit di {@code lh-test-support}. Solo gli endpoint sotto
 * {@code /v1/portal/} possono dichiararla ({@code MemberEndpointGuard} lo verifica all'avvio).
 *
 * <p>Profilo {@code enterprise} ({@code identity.mode=oidc}): il membro viene solo dal token (via {@code sub} e
 * proiezione locale {@code subjectRef → memberId}); un {@code memberId} nella richiesta è un errore ({@code 400
 * MEMBER_FROM_TOKEN}, nel percorso {@code 403}); un operatore non agisce mai come membro ({@code 403
 * MEMBER_REQUIRED}, salvo {@link Mode#OPTIONAL}: vista generica). Profilo {@code demo}: il membro è il
 * {@code memberId} esplicito o l'header {@code X-LH-Member}, come oggi (CLAUDE.md regola 6-bis).
 */
@Documented
@EndpointAccess
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface MemberEndpoint {

    /** Come si comporta l'handler rispetto al membro (default: {@link Mode#REQUIRED}). */
    Mode value() default Mode.REQUIRED;

    /**
     * Solo per i percorsi legacy deprecati: nome della variabile di percorso che porta l'id del membro
     * ({@code /v1/portal/wallets/{memberId}}). Vale soltanto nel profilo {@code demo}; in {@code enterprise} un id nel
     * percorso dà {@code 403 MEMBER_FROM_TOKEN}. Ammesso solo su handler {@link Deprecated}, con la variabile presente
     * nel pattern. L'handler non la lega: la legge l'interceptor e la restituisce nel {@link MemberPrincipal}.
     */
    String demoPathVariable() default "";

    /** Modo di risoluzione del membro. */
    enum Mode {
        /** Serve un membro: il token di un membro registrato; senza, 403/404/409. Il parametro è un {@link MemberPrincipal}. */
        REQUIRED,
        /** Personalizza per il membro se c'è, altrimenti vista generica (un operatore, BO-17). Parametro {@link MemberPrincipal}. */
        OPTIONAL,
        /** Registrazione: il token di un account non ancora legato a un membro. Il parametro è un {@link MemberSubject}. */
        REGISTRATION
    }
}
