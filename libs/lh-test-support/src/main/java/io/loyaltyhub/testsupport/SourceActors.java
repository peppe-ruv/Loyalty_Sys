package io.loyaltyhub.testsupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Identità di fonte per i test (Q-492): l'ingresso delle azioni ({@code POST /v1/events}, {@code /v1/events/batch},
 * {@code /v1/transactions}) vuole {@code SOURCE:src-<codice>} (o ADMIN). I test che non parlano di identità inviano come
 * la fonte che l'evento stesso dichiara, cioè come farebbe un sistema esterno reale; un'identità esplicita (ADMIN,
 * ANALYST…) resta quella indicata dal test.
 */
public final class SourceActors {

    public static final String URN_PREFIX = "urn:loyaltyhub:source:";
    /** Fonte usata quando il corpo non dichiara alcuna {@code source} (per esempio i casi di forma non valida). */
    public static final String FALLBACK = "SOURCE:src-ecommerce";
    /**
     * Identità usata quando il corpo dichiara una {@code source} che nessun client {@code src-<codice>} può avere (URN
     * estraneo, codice vuoto o in maiuscolo): solo {@code ADMIN} non è soggetto al legame con la fonte e arriva alla
     * pipeline, che la rifiuta come fonte sconosciuta.
     */
    public static final String ADMIN = "ADMIN:tb.admin";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Pattern CODE = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private SourceActors() {
    }

    /** {@code SOURCE:src-<codice>} per il codice fonte. */
    public static String of(String sourceCode) {
        return "SOURCE:src-" + sourceCode;
    }

    /** {@code true} per i tre percorsi di ingresso delle fonti. */
    public static boolean isIngress(String method, String path) {
        if (!"POST".equalsIgnoreCase(method) || path == null) {
            return false;
        }
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        return p.equals("/v1/events") || p.equals("/v1/events/batch") || p.equals("/v1/transactions");
    }

    /**
     * L'identità da inviare: quella esplicita del test se c'è, altrimenti, sui percorsi di ingresso, la fonte del corpo.
     * Su ogni altro percorso resta {@code actor} (anche {@code null}).
     */
    public static String actorFor(String method, String path, String actor, Object body) {
        if (actor != null || !isIngress(method, path)) {
            return actor;
        }
        return forBody(body);
    }

    /**
     * {@code SOURCE:src-<codice>} dalla {@code source} del corpo (URN o codice breve; primo elemento di un batch);
     * {@link #FALLBACK} se il corpo non dichiara alcuna {@code source}; {@link #ADMIN} se ne dichiara una che nessun
     * client di fonte può avere.
     */
    public static String forBody(Object body) {
        String source = sourceOf(body);
        if (source == null || source.isBlank()) {
            return FALLBACK;
        }
        String code = source.startsWith(URN_PREFIX) ? source.substring(URN_PREFIX.length()) : source;
        return CODE.matcher(code).matches() ? of(code) : ADMIN;
    }

    private static String sourceOf(Object body) {
        try {
            if (body instanceof String raw) {
                return sourceOf(MAPPER.readTree(raw));
            }
            if (body instanceof byte[] bytes) {
                return sourceOf(MAPPER.readTree(bytes));
            }
            if (body instanceof Map<?, ?> map) {
                return map.get("source") instanceof String s ? s : null;
            }
            if (body instanceof List<?> list) {
                return list.isEmpty() ? null : sourceOf(list.getFirst());
            }
            if (body instanceof JsonNode node) {
                if (node.isArray()) {
                    return node.isEmpty() ? null : sourceOf(node.get(0));
                }
                JsonNode s = node.get("source");
                return s != null && s.isString() ? s.asString() : null;
            }
            if (body != null) {
                return sourceOf(MAPPER.valueToTree(body));
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }
}
