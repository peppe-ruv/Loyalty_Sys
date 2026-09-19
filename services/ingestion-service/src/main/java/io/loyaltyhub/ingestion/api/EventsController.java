package io.loyaltyhub.ingestion.api;

import io.loyaltyhub.ingestion.application.IngestionService;
import io.loyaltyhub.ingestion.domain.IngestResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingresso delle azioni premianti (docs/servizi/ingestion-service.md §3): {@code POST /v1/events}.
 * Errori di forma → {@code 400}; tutto il resto → {@code 202} con lo {@code status} dell'esito
 * (la fonte non deve ritentare su un rifiuto di business).
 */
@RestController
@RequestMapping("/v1")
public class EventsController {

    private final IngestionService ingestion;

    public EventsController(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @PostMapping(
            path = "/events",
            consumes = {"application/json", "application/cloudevents+json"})
    public ResponseEntity<IngestResult> ingest(@RequestBody InboundEventRequest request) {
        IngestResult result = ingestion.ingest(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }
}
