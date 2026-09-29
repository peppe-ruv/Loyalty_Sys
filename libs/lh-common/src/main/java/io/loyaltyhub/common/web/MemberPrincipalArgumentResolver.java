package io.loyaltyhub.common.web;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Consegna al controller il {@link MemberPrincipal} o il {@link MemberSubject} che {@link EndpointAccessInterceptor} ha
 * risolto (Q-410, ADR-048). Legge <strong>solo</strong> l'attributo di richiesta: non legge mai query, corpo, percorso o
 * header. Se l'attributo manca (il parametro è su un handler senza {@link MemberEndpoint}, o del modo sbagliato) risponde
 * {@code 403 ENDPOINT_NOT_DECLARED}: fallisce chiuso, mai con un membro inventato.
 */
public class MemberPrincipalArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        Class<?> type = parameter.getParameterType();
        return type == MemberPrincipal.class || type == MemberSubject.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String attribute = parameter.getParameterType() == MemberPrincipal.class
                ? MemberPrincipal.ATTRIBUTE : MemberSubject.ATTRIBUTE;
        Object value = webRequest.getAttribute(attribute, RequestAttributes.SCOPE_REQUEST);
        if (value == null) {
            throw LhException.endpointNotDeclared();
        }
        return value;
    }
}
