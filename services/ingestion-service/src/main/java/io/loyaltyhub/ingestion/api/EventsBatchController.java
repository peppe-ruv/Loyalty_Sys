package io.loyaltyhub.ingestion.api;

import io.loyaltyhub.common.web.GlobalExceptionHandler;
import io.loyaltyhub.ingestion.application.BatchIngestionService;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.net.URI;

/**
 * Ingresso batch (F2-ING-01, docs/18 §3.6; docs/servizi/ingestion-service.md §3): {@code POST /v1/events/batch}, array
 * JSON di al più {@value BatchIngestionService#MAX_EVENTS} CloudEvent (modo <em>batched</em> di CloudEvents), {@code 202}
 * con l'esito di ogni elemento nell'ordine ricevuto ({@link BatchIngestionService}). Il limite di frequenza per
 * indirizzo conta un evento per elemento ({@link IngressRateLimitFilter#admitEvents}); il corpo ha un tetto in byte
 * ({@link BatchBodyLimitFilter}).
 */
@RestController
@RequestMapping("/v1")
public class EventsBatchController {

    private final BatchIngestionService batch;
    private final IngressRateLimitFilter rateLimit;

    public EventsBatchController(BatchIngestionService batch, IngressRateLimitFilter rateLimit) {
        this.batch = batch;
        this.rateLimit = rateLimit;
    }

    /** Limite di frequenza superato dal batch: {@code 429} con {@code Retry-After}. */
    static final class RateLimited extends RuntimeException {
        private final long retryAfterSeconds;

        RateLimited(String detail, long retryAfterSeconds) {
            super(detail);
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    // Il corpo arriva come HttpEntity (niente @RequestBody di Spring): l'annotazione @RequestBody qui è quella di
    // OpenAPI e descrive gli elementi come InboundEventRequest; il servizio legge l'albero JSON per dare un esito anche
    // agli elementi malformati.
    @PostMapping(
            path = "/events/batch",
            consumes = {"application/json", "application/cloudevents-batch+json"})
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequestBody(required = true, content = @Content(array = @ArraySchema(
            maxItems = BatchIngestionService.MAX_EVENTS,
            schema = @Schema(implementation = InboundEventRequest.class))))
    @ApiResponse(responseCode = "400", description = "Corpo non JSON o non array; corpo oltre il tetto in streaming",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "413", description = "BATCH_BODY_TOO_LARGE: corpo oltre il tetto in byte",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "BATCH_EMPTY, BATCH_TOO_LARGE",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "RATE_LIMITED: eventi al minuto per indirizzo superati (Retry-After)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<BatchIngestionService.BatchResult> ingestBatch(HttpEntity<JsonNode> request,
                                                                        HttpServletRequest http) {
        JsonNode body = request.getBody();
        int events = BatchIngestionService.requireBatch(body);
        IngressRateLimitFilter.Admission admission = rateLimit.admitEvents(http, events);
        if (!admission.admitted()) {
            String detail = admission.tooMany()
                    ? "Un batch di " + events + " eventi supera il limite di " + rateLimit.perMinute()
                    + " eventi al minuto per questo indirizzo: dividilo in batch più piccoli"
                    : "Limite di " + rateLimit.perMinute() + " eventi al minuto superato per questo indirizzo: riprova tra poco";
            throw new RateLimited(detail, Math.max(1, (admission.retryAfterMs() + 999) / 1000));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(batch.ingest(body));
    }

    @ExceptionHandler(RateLimited.class)
    ResponseEntity<ProblemDetail> onRateLimited(RateLimited e, HttpServletRequest http) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        pd.setType(URI.create(GlobalExceptionHandler.PROBLEM_TYPE_PREFIX + "rate-limited"));
        pd.setTitle("Troppe richieste");
        pd.setInstance(URI.create(http.getRequestURI()));
        pd.setProperty("code", "RATE_LIMITED");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(e.retryAfterSeconds))
                .body(pd);
    }
}
