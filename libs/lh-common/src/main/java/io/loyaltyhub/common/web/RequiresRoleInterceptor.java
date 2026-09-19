package io.loyaltyhub.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

/** Applica {@link RequiresRole} prima del controller (docs/06 §3). Viola ⇒ {@link LhException#forbiddenRole}. */
public class RequiresRoleInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequiresRole annotation = method.getMethodAnnotation(RequiresRole.class);
        if (annotation == null) {
            annotation = method.getBeanType().getAnnotation(RequiresRole.class);
        }
        if (annotation == null) {
            return true;
        }
        Role role = ActorHolder.get().role();
        if (role == Role.ADMIN) {
            return true;
        }
        Role[] allowed = annotation.value();
        if (allowed.length == 0) {
            // Regola "scrittura": qualunque ruolo tranne ANALYST.
            if (role.isReadOnly()) {
                throw LhException.forbiddenRole("Il ruolo " + role + " non può eseguire questa operazione");
            }
            return true;
        }
        if (Arrays.asList(allowed).contains(role)) {
            return true;
        }
        throw LhException.forbiddenRole("Serve uno dei ruoli " + Arrays.toString(allowed) + " (attuale: " + role + ")");
    }
}
