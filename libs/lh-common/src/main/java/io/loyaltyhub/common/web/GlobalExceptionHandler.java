package io.loyaltyhub.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.net.URI;
import java.util.List;

/**
 * Traduce le eccezioni in {@code application/problem+json} (RFC 9457, docs/06 §2).
 * {@code type = urn:loyaltyhub:problem:<suffix>}, più le proprietà {@code code} e {@code errors}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public static final String PROBLEM_TYPE_PREFIX = "urn:loyaltyhub:problem:";

    @ExceptionHandler(LhException.class)
    public ResponseEntity<ProblemDetail> onLhException(LhException ex, HttpServletRequest request) {
        ProblemDetail pd = base(ex.status(), ex.typeSuffix(), title(ex.status()), ex.getMessage(), request);
        pd.setProperty("code", ex.code());
        if (!ex.errors().isEmpty()) {
            pd.setProperty("errors", ex.errors());
        }
        return ResponseEntity.status(ex.status()).body(pd);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> onBeanValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<LhException.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new LhException.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        ProblemDetail pd = base(HttpStatus.UNPROCESSABLE_ENTITY, "validation", "Dati non validi",
                "Uno o più campi non sono validi", request);
        pd.setProperty("code", "VALIDATION");
        pd.setProperty("errors", errors);
        return ResponseEntity.unprocessableEntity().body(pd);
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> onUnreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException ex, HttpServletRequest request) {
        // Corpo assente, non JSON o con un valore del tipo sbagliato: errore di forma del client → 400, non 500
        // (docs/06 §2). Il dettaglio non riporta il messaggio del parser (può contenere il corpo ricevuto).
        log.debug("Corpo non leggibile su {}: {}", request != null ? request.getRequestURI() : "?", ex.getMessage());
        ProblemDetail pd = base(HttpStatus.BAD_REQUEST, "bad-request", title(HttpStatus.BAD_REQUEST),
                "Corpo della richiesta assente o non leggibile come JSON valido", request);
        pd.setProperty("code", "BAD_REQUEST");
        return ResponseEntity.badRequest().body(pd);
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> onArgumentTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        // Parametro di query/percorso non convertibile (es. una data malformata in un filtro) → 400.
        ProblemDetail pd = base(HttpStatus.BAD_REQUEST, "bad-request", title(HttpStatus.BAD_REQUEST),
                "Parametro non valido: " + ex.getName(), request);
        pd.setProperty("code", "BAD_REQUEST");
        return ResponseEntity.badRequest().body(pd);
    }

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetail> onMissingParameter(
            org.springframework.web.bind.MissingServletRequestParameterException ex, HttpServletRequest request) {
        // Parametro di query obbligatorio assente (es. `metric` dei KPI): parametro errato del client → 400 (docs/06 §2).
        ProblemDetail pd = base(HttpStatus.BAD_REQUEST, "bad-request", title(HttpStatus.BAD_REQUEST),
                "Parametro obbligatorio assente: " + ex.getParameterName(), request);
        pd.setProperty("code", "BAD_REQUEST");
        return ResponseEntity.badRequest().body(pd);
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> onNoResource(
            org.springframework.web.servlet.resource.NoResourceFoundException ex, HttpServletRequest request) {
        // Percorso non mappato (es. la radice `/`): è un 404, non un errore interno.
        ProblemDetail pd = base(HttpStatus.NOT_FOUND, "not-found", title(HttpStatus.NOT_FOUND),
                "Nessuna risorsa per questo percorso", request);
        pd.setProperty("code", "NOT_FOUND");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(pd);
    }

    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
    public void onAsyncNotUsable(Exception ex) {
        // Cliente SSE disconnesso (es. il rail eventi che chiude l'EventSource): la risposta non è più
        // scrivibile. Non è un 500 e non va reso come problem+json (il content-type è text/event-stream):
        // ritorno void = nessun corpo. Solo un log a DEBUG.
        log.debug("Stream chiuso dal client: {}", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> onUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Errore imprevisto su {}", request != null ? request.getRequestURI() : "?", ex);
        ProblemDetail pd = base(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "Errore interno",
                "Si è verificato un errore imprevisto", request);
        pd.setProperty("code", "INTERNAL_ERROR");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(pd);
    }

    private ProblemDetail base(HttpStatus status, String typeSuffix, String title, String detail, HttpServletRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(PROBLEM_TYPE_PREFIX + typeSuffix));
        pd.setTitle(title);
        if (request != null) {
            pd.setInstance(URI.create(request.getRequestURI()));
        }
        return pd;
    }

    private String title(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "Richiesta non valida";
            case FORBIDDEN -> "Operazione non consentita";
            case NOT_FOUND -> "Risorsa non trovata";
            case CONFLICT -> "Conflitto";
            case UNPROCESSABLE_ENTITY -> "Dati non validi";
            case SERVICE_UNAVAILABLE -> "Dipendenza non disponibile";
            default -> status.getReasonPhrase();
        };
    }
}
