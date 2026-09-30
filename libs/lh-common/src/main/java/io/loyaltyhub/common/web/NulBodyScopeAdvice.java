package io.loyaltyhub.common.web;

import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.io.IOException;
import java.lang.reflect.Type;

/**
 * Accende il rifiuto del NUL ({@link NulRejectingModule}) solo per la durata della conversione del corpo di una richiesta
 * (Q-532 causa (3), F2-SEC-12): {@code beforeBodyRead} lo accende, {@code afterBodyRead} e {@code handleEmptyBody} lo
 * spengono. Se la lettura fallisce (corpo non valido, NUL), l'handler non viene chiamato e la richiesta passa agli
 * handler degli errori, che non leggono JSON ricevuto.
 *
 * <p>Non si accende per un handler {@link NulTolerantBody} (metodo o classe). Così il resto del thread della richiesta
 * (il parsing che un handler fa da sé di un testo ricevuto, per esempio il file d'import JSON, Q-371) non è toccato.
 */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class NulBodyScopeAdvice extends RequestBodyAdviceAdapter {

    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage, MethodParameter parameter, Type targetType,
                                           Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        if (!tolerant(parameter)) {
            NulScope.enter();
        }
        return inputMessage;
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter, Type targetType,
                                Class<? extends HttpMessageConverter<?>> converterType) {
        NulScope.exit();
        return body;
    }

    @Override
    public Object handleEmptyBody(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
                                  Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        NulScope.exit();
        return body;
    }

    private static boolean tolerant(MethodParameter parameter) {
        return (parameter.getMethod() != null
                && AnnotatedElementUtils.hasAnnotation(parameter.getMethod(), NulTolerantBody.class))
                || AnnotatedElementUtils.hasAnnotation(parameter.getContainingClass(), NulTolerantBody.class);
    }
}
