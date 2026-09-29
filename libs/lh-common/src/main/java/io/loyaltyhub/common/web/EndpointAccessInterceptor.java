package io.loyaltyhub.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.List;

/**
 * Autorizzazione degli endpoint prima del controller: deny by default (F2-SEC-09, ADR-042, CLAUDE.md regola 18,
 * docs/06 §3.2), in ogni profilo.
 *
 * <ul>
 *   <li>Ogni metodo di controller dichiara l'accesso con {@link RequiresRole} o {@link PublicEndpoint}, sul metodo o
 *       sulla classe; la dichiarazione sul metodo prevale. Se il metodo o la classe portano entrambe, vince
 *       {@link RequiresRole} (la più restrittiva).</li>
 *   <li>{@link RequiresRole}: semantica invariata (docs/06 §3); violata ⇒ {@link LhException#forbiddenRole}.</li>
 *   <li>{@link PublicEndpoint} con motivo non vuoto: nessun controllo di ruolo.</li>
 *   <li>Nessuna dichiarazione, o un {@code @PublicEndpoint} senza motivo ⇒ {@link LhException#endpointNotDeclared}
 *       ({@code 403 ENDPOINT_NOT_DECLARED}); il log dice solo classe e metodo, mai percorso, parametri o attore.</li>
 * </ul>
 *
 * <p>Restano fuori i controller dei framework (errore di Spring Boot, OpenAPI di springdoc): non sono codice del
 * prodotto e non portano annotazioni; nel profilo {@code enterprise} li protegge comunque il filtro OIDC.
 */
public class EndpointAccessInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(EndpointAccessInterceptor.class);

    /** Package dei controller dei framework, esclusi dal deny by default. */
    static final List<String> FRAMEWORK_PACKAGES = List.of("org.springframework.", "org.springdoc.");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        Class<?> beanType = method.getBeanType();
        if (isFramework(beanType)) {
            return true;
        }
        RequiresRole role = method.getMethodAnnotation(RequiresRole.class);
        PublicEndpoint open = method.getMethodAnnotation(PublicEndpoint.class);
        if (role == null && open == null) {
            role = AnnotatedElementUtils.findMergedAnnotation(beanType, RequiresRole.class);
            open = AnnotatedElementUtils.findMergedAnnotation(beanType, PublicEndpoint.class);
        }
        if (role != null) {
            check(role);
            return true;
        }
        if (open != null && !open.reason().isBlank()) {
            return true;
        }
        log.warn("Endpoint rifiutato: nessuna dichiarazione di accesso valida (deny by default) su {}#{}",
                beanType.getName(), method.getMethod().getName());
        throw LhException.endpointNotDeclared();
    }

    private static void check(RequiresRole annotation) {
        Role role = ActorHolder.get().role();
        if (role == Role.ADMIN) {
            return;
        }
        Role[] allowed = annotation.value();
        if (allowed.length == 0) {
            // Regola "scrittura": qualunque ruolo tranne ANALYST.
            if (role.isReadOnly()) {
                throw LhException.forbiddenRole("Il ruolo " + role + " non può eseguire questa operazione");
            }
            return;
        }
        if (Arrays.asList(allowed).contains(role)) {
            return;
        }
        throw LhException.forbiddenRole("Serve uno dei ruoli " + Arrays.toString(allowed) + " (attuale: " + role + ")");
    }

    static boolean isFramework(Class<?> beanType) {
        String name = beanType.getName();
        return FRAMEWORK_PACKAGES.stream().anyMatch(name::startsWith);
    }
}
