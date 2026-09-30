package io.loyaltyhub.common.web;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Dove {@link NulRejectingModule} rifiuta un NUL: solo leggendo il corpo di una richiesta HTTP gestita da un handler MVC
 * del prodotto, e non se l'handler è {@link NulTolerantBody}. Fuori da una richiesta (consumer Kafka, caricamento dei
 * seed, worker dei lavori di import, test) il mapper di Spring Boot si comporta come prima: quei flussi hanno le loro
 * regole sui NUL (per esempio la riga di un file d'import respinta come {@code INVALID}, Q-371) e non passano da un
 * {@code 400}.
 */
final class NulScope {

    private NulScope() {
    }

    static boolean rejecting() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return false;
        }
        Object handler = attributes.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE,
                RequestAttributes.SCOPE_REQUEST);
        if (handler instanceof HandlerMethod method) {
            return !AnnotatedElementUtils.hasAnnotation(method.getMethod(), NulTolerantBody.class)
                    && !AnnotatedElementUtils.hasAnnotation(method.getBeanType(), NulTolerantBody.class);
        }
        return true;
    }
}
