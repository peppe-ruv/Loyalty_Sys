package io.loyaltyhub.common.web;

/**
 * Parametri di paginazione degli elenchi {@code {items, page}} (docs/06 §2): {@code page} da 0, {@code size} da 1 a
 * {@value #MAX_SIZE}; oltre il massimo la dimensione è ridotta a {@value #MAX_SIZE}. SPEC-GAP: Q-332 (estende Q-317 e
 * Q-323 a tutti i servizi) — {@code page} negativa o {@code size} minore di 1 sono «parametri errati»: 400
 * {@code BAD_REQUEST}, mai corretti in silenzio. Q-532 causa (2), F2-SEC-12: anche una {@code page} così grande che
 * {@code page × size} non entra in un {@code int} (es. {@code page=2147483646}) è un parametro errato: 400, non un
 * {@code OFFSET} negativo per overflow che Postgres rifiuta con un 500.
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
        int effectiveSize = Math.min(size, MAX_SIZE);
        // Il prodotto si calcola in long: il confine esatto (page × size = Integer.MAX_VALUE) è ammesso.
        if ((long) page * effectiveSize > Integer.MAX_VALUE) {
            throw LhException.badRequest("Parametro page fuori scala: page × size non può superare " + Integer.MAX_VALUE);
        }
        return new PageParams(page, effectiveSize);
    }

    /**
     * Righe da saltare: {@code page × size}. Con {@code Math.multiplyExact}: un record costruito senza {@link #of}
     * non può mai restituire un valore negativo per overflow, fallisce (Q-532 causa (2)).
     */
    public int offset() {
        return Math.multiplyExact(page, size);
    }
}
