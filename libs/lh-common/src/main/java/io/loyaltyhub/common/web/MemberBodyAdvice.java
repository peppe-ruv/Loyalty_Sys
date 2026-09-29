package io.loyaltyhub.common.web;

import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import tools.jackson.databind.JsonNode;

import java.lang.reflect.Array;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Nel profilo {@code enterprise} rifiuta con {@code 400 MEMBER_FROM_TOKEN} un corpo che porta un {@code memberId} non
 * nullo, a qualunque profondità (fino a {@value #MAX_DEPTH}), su un handler {@link MemberEndpoint} (Q-553, D6, ADR-048):
 * il membro è quello del token. La difesa sul corpo è generica e non dipende dalla disciplina di ogni controller.
 *
 * <p>Guarda le componenti dei {@code record} e dei bean {@code io.loyaltyhub.*}, i valori delle mappe e gli elementi di
 * elenchi e array, le chiavi di un {@link JsonNode}; il nome vale in qualunque grafia
 * ({@link MemberPrincipals#isMemberIdName}). Un {@code memberId} nullo (assente o {@code null}) passa. Nel profilo
 * {@code demo} non fa nulla: il campo deprecato resta ammesso e lo fonde {@link MemberPrincipal#merge}.
 */
@ControllerAdvice
public class MemberBodyAdvice extends RequestBodyAdviceAdapter {

    /** Profondità massima ispezionata (la radice è 0). */
    static final int MAX_DEPTH = 4;

    private static final String BEAN_PACKAGE_PREFIX = "io.loyaltyhub.";

    private final IdentityMode mode;

    public MemberBodyAdvice(IdentityMode mode) {
        this.mode = mode == null ? IdentityMode.HEADER : mode;
    }

    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        if (mode != IdentityMode.OIDC || methodParameter.getMethod() == null) {
            return false;
        }
        return EndpointAccessInterceptor.resolve(methodParameter.getMethod(), methodParameter.getContainingClass())
                .member() != null;
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter, Type targetType,
                                Class<? extends HttpMessageConverter<?>> converterType) {
        if (carriesMemberId(body)) {
            throw LhException.memberFromToken(false);
        }
        return body;
    }

    /** Vero se {@code body} porta un {@code memberId} non nullo entro {@link #MAX_DEPTH}. */
    static boolean carriesMemberId(Object body) {
        return body != null && scan(body, 0, java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static boolean scan(Object value, int depth, Set<Object> seen) {
        if (value == null || depth > MAX_DEPTH) {
            return false;
        }
        if (value instanceof JsonNode node) {
            return scanJson(node, depth);
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() != null && MemberPrincipals.isMemberIdName(e.getKey().toString()) && e.getValue() != null) {
                    return true;
                }
                if (scan(e.getValue(), depth + 1, seen)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Collection<?> items) {
            for (Object item : items) {
                if (scan(item, depth + 1, seen)) {
                    return true;
                }
            }
            return false;
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            if (type.getComponentType().isPrimitive()) {
                return false;
            }
            for (int i = 0; i < Array.getLength(value); i++) {
                if (scan(Array.get(value, i), depth + 1, seen)) {
                    return true;
                }
            }
            return false;
        }
        if (!type.getName().startsWith(BEAN_PACKAGE_PREFIX) || !seen.add(value)) {
            return false;
        }
        return type.isRecord() ? scanRecord(value, depth, seen) : scanBean(value, depth, seen);
    }

    private static boolean scanRecord(Object record, int depth, Set<Object> seen) {
        for (java.lang.reflect.RecordComponent component : record.getClass().getRecordComponents()) {
            Object field = read(component.getAccessor(), record);
            if (MemberPrincipals.isMemberIdName(component.getName()) && field != null) {
                return true;
            }
            if (scan(field, depth + 1, seen)) {
                return true;
            }
        }
        return false;
    }

    private static boolean scanBean(Object bean, int depth, Set<Object> seen) {
        for (Class<?> c = bean.getClass(); c != null && c.getName().startsWith(BEAN_PACKAGE_PREFIX); c = c.getSuperclass()) {
            for (java.lang.reflect.Field field : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Object v = readField(field, bean);
                if (MemberPrincipals.isMemberIdName(field.getName()) && v != null) {
                    return true;
                }
                if (scan(v, depth + 1, seen)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean scanJson(JsonNode node, int depth) {
        if (depth > MAX_DEPTH) {
            return false;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                if (MemberPrincipals.isMemberIdName(e.getKey()) && !e.getValue().isNull() && !e.getValue().isMissingNode()) {
                    return true;
                }
                if (scanJson(e.getValue(), depth + 1)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                if (scanJson(item, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Object read(java.lang.reflect.Method accessor, Object target) {
        try {
            accessor.setAccessible(true);
            return accessor.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Un corpo che non si riesce a ispezionare non passa (chiude, mai aperto).
            throw LhException.badRequest("Corpo della richiesta non valido");
        }
    }

    private static Object readField(java.lang.reflect.Field field, Object target) {
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw LhException.badRequest("Corpo della richiesta non valido");
        }
    }
}
