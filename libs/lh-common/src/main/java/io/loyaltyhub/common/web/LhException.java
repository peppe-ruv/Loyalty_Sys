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

    /**
     * 403: una fonte autenticata dichiara un {@code source} diverso dal proprio client ({@code src-<codice>}); nulla è
     * salvato né pubblicato (Q-492, docs/06 §3.2). Il valore dichiarato dal chiamante non si riporta nel dettaglio.
     */
    public static LhException sourceMismatch(String detail) {
        return new LhException(HttpStatus.FORBIDDEN, "source-mismatch", "SOURCE_MISMATCH", detail, null);
    }

    /**
     * 403: l'endpoint non dichiara chi può chiamarlo ({@link RequiresRole} o {@link PublicEndpoint}) ed è rifiutato
     * a tutti (deny by default, F2-SEC-09). È un errore del codice, non del chiamante.
     */
    public static LhException endpointNotDeclared() {
        return new LhException(HttpStatus.FORBIDDEN, "endpoint-not-declared", "ENDPOINT_NOT_DECLARED",
                "L'endpoint non dichiara chi può chiamarlo ed è rifiutato (deny by default).", null);
    }

    /** Secondi di {@code Retry-After} per {@code 409 MEMBER_NOT_LINKED} (il fatto {@code member.registered} sta per arrivare). */
    public static final int MEMBER_NOT_LINKED_RETRY_AFTER_SECONDS = 2;

    /**
     * Il membro è quello del token e la richiesta ne indica uno (Q-553, D6, ADR-048). {@code path = false}: un
     * {@code memberId} in query, in un campo form, nel corpo o in {@code X-LH-Member} ⇒ 400; {@code path = true}: un id nel
     * percorso (legacy) ⇒ 403. Il valore ricevuto non si riporta nel dettaglio, neppure se è il proprio.
     */
    public static LhException memberFromToken(boolean path) {
        return new LhException(path ? HttpStatus.FORBIDDEN : HttpStatus.BAD_REQUEST, "member-from-token",
                "MEMBER_FROM_TOKEN", path
                        ? "Il membro è quello del token: l'identificativo non va indicato nel percorso."
                        : "Il membro è quello del token: non indicare memberId nella richiesta.", null);
    }

    /**
     * 403: la funzione è del membro autenticato e il chiamante non lo è (un operatore, anche con il ruolo {@code MEMBER}
     * tra i suoi, Q-554); un operatore non agisce mai come membro.
     */
    public static LhException memberRequired() {
        return new LhException(HttpStatus.FORBIDDEN, "member-required", "MEMBER_REQUIRED",
                "Questa funzione è riservata al membro autenticato.", null);
    }

    /** 404: il {@code sub} del token non ha un membro (solo member-service, fonte autorevole): serve la registrazione. */
    public static LhException memberNotRegistered() {
        return new LhException(HttpStatus.NOT_FOUND, "member-not-registered", "MEMBER_NOT_REGISTERED",
                "Nessun membro registrato per questo account: completa la registrazione.", null);
    }

    /**
     * 409: il {@code sub} del token non è ancora legato a un membro in questo servizio (il fatto di registrazione non è
     * ancora arrivato): ritentare dopo {@code Retry-After} ({@link #MEMBER_NOT_LINKED_RETRY_AFTER_SECONDS}).
     */
    public static LhException memberNotLinked() {
        return new LhException(HttpStatus.CONFLICT, "member-not-linked", "MEMBER_NOT_LINKED",
                "Il membro non è ancora collegato a questo servizio: riprova tra poco.", null);
    }

    /** 400 (solo demo): le fonti del membro (parametro, header, percorso, corpo) indicano membri diversi. */
    public static LhException memberMismatch() {
        return new LhException(HttpStatus.BAD_REQUEST, "member-mismatch", "MEMBER_MISMATCH",
                "Le indicazioni del membro nella richiesta non coincidono.", null);
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
