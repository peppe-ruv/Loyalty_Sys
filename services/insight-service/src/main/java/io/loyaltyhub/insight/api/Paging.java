package io.loyaltyhub.insight.api;

import io.loyaltyhub.common.web.LhException;

import java.time.Instant;

/**
 * Paginazione degli elenchi di insight (docs/06 §2): {@code ?page=0&size=20}, {@code size} massimo 100 (oltre è
 * limitata a 100). {@code limit} resta accettato come sinonimo di {@code size} per i chiamanti esistenti.
 */
// SPEC-GAP: Q-317, Q-323 — size (o limit) < 1 e page < 0 non sono portati a un valore valido: 400 BAD_REQUEST.
record Paging(int page, int size) {

    static final int MAX_SIZE = 100;

    static Paging of(Integer page, Integer size, Integer limit, int defaultSize) {
        int p = page == null ? 0 : page;
        Integer requested = size != null ? size : limit;
        int s = requested == null ? defaultSize : requested;
        if (p < 0) {
            throw LhException.badRequest("Parametro page non valido (atteso ≥ 0): " + p);
        }
        if (s < 1) {
            throw LhException.badRequest("Parametro size non valido (atteso tra 1 e " + MAX_SIZE + "): " + s);
        }
        return new Paging(p, Math.min(s, MAX_SIZE));
    }

    int offset() {
        return page * size;
    }

    /** Istante ISO-8601 di un filtro; assente = nessun filtro; non valido = 400. */
    static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (RuntimeException e) {
            throw LhException.badRequest("Istante non valido (atteso ISO-8601): " + value);
        }
    }
}
