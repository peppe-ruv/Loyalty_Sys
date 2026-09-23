package io.loyaltyhub.common.web;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * Eccezione applicativa mappata su {@code application/problem+json} (RFC 9457, docs/06 §2).
 * {@code code} è stabile e usato dal frontend; {@code detail} è in italiano e mostrabile all'utente.
 */
public class LhException extends RuntimeException {

    /** Errore su un singolo campo (chiave {@code errors[]} del problem). */
    public record FieldError(String field, String message) {
    }

    private final HttpStatus status;
    private final String typeSuffix;
    private final String code;
    private final List<FieldError> errors;

    public LhException(HttpStatus status, String typeSuffix, String code, String detail, List<FieldError> errors) {
        super(detail);
        this.status = status;
        this.typeSuffix = typeSuffix;
        this.code = code;
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public HttpStatus status() {
        return status;
    }

    public String typeSuffix() {
        return typeSuffix;
    }

    public String code() {
        return code;
    }

    public List<FieldError> errors() {
        return errors;
    }

    // --- Fabbriche per stato (docs/06 §2) ---

    public static LhException badRequest(String detail) {
        return new LhException(HttpStatus.BAD_REQUEST, "bad-request", "BAD_REQUEST", detail, null);
    }

    public static LhException forbiddenRole(String detail) {
        return new LhException(HttpStatus.FORBIDDEN, "forbidden-role", "FORBIDDEN_ROLE", detail, null);
    }

    public static LhException notFound(String detail) {
        return new LhException(HttpStatus.NOT_FOUND, "not-found", "NOT_FOUND", detail, null);
    }

    public static LhException conflict(String code, String detail) {
        return new LhException(HttpStatus.CONFLICT, "conflict", code, detail, null);
    }

    /** 410: la risorsa esisteva ma non è più utilizzabile (es. {@code COUPON_EXPIRED}). */
    public static LhException gone(String code, String detail) {
        return new LhException(HttpStatus.GONE, "gone", code, detail, null);
    }

    /** 422: regola di business violata; {@code code} specifico elencato nelle schede servizio. */
    public static LhException validation(String code, String detail) {
        return new LhException(HttpStatus.UNPROCESSABLE_ENTITY, "validation", code, detail, null);
    }

    public static LhException validation(String code, String detail, List<FieldError> errors) {
        return new LhException(HttpStatus.UNPROCESSABLE_ENTITY, "validation", code, detail, errors);
    }

    public static LhException dependencyUnavailable(String detail) {
        return new LhException(HttpStatus.SERVICE_UNAVAILABLE, "dependency-unavailable", "DEPENDENCY_UNAVAILABLE", detail, null);
    }
}
