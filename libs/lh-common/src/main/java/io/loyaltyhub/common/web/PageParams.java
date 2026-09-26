package io.loyaltyhub.common.web;

/**
 * Parametri di paginazione degli elenchi {@code {items, page}} (docs/06 §2): {@code page} da 0, {@code size} da 1 a
 * {@value #MAX_SIZE}; oltre il massimo la dimensione è ridotta a {@value #MAX_SIZE}. SPEC-GAP: Q-332 (estende Q-317 e
 * Q-323 a tutti i servizi) — {@code page} negativa o {@code size} minore di 1 sono «parametri errati»: 400
 * {@code BAD_REQUEST}, mai corretti in silenzio.
 */
public record PageParams(int page, int size) {

    public static final int MAX_SIZE = 100;

    public static PageParams of(int page, int size) {
        if (page < 0) {
            throw LhException.badRequest("Parametro page non valido (atteso ≥ 0): " + page);
        }
        if (size < 1) {
            throw LhException.badRequest("Parametro size non valido (atteso tra 1 e " + MAX_SIZE + "): " + size);
        }
        return new PageParams(page, Math.min(size, MAX_SIZE));
    }

    /** Righe da saltare: {@code page × size}. */
    public int offset() {
        return page * size;
    }
}
