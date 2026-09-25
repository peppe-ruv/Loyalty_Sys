package io.loyaltyhub.ingestion.api;

import io.loyaltyhub.common.event.LhSource;
import io.loyaltyhub.common.web.ActorHolder;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.ingestion.application.ActionReplayService;
import io.loyaltyhub.ingestion.application.IngestionService;
import io.loyaltyhub.ingestion.domain.IngestResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Ingresso delle azioni premianti (docs/servizi/ingestion-service.md §3): {@code POST /v1/events}.
 * Errori di forma → {@code 400} (anche {@code source} in forma breve, Q-258); tutto il resto → {@code 202} con lo
 * {@code status} dell'esito
 * (la fonte non deve ritentare su un rifiuto di business).
 *
 * <p>Con l'header {@code X-LH-Reprocess} (solo ADMIN) è il <em>riprocessa</em> DLQ di insight (ADR-002
 * eccezione 1): l'azione già accettata con lo stesso id viene ripubblicata ({@link ActionReplayService}).
 */
@RestController
@RequestMapping("/v1")
public class EventsController {

    /** Header del riprocessa DLQ; il valore è l'id della voce DLQ di insight (solo per i log). */
    public static final String REPROCESS_HEADER = "X-LH-Reprocess";

    private final IngestionService ingestion;
    private final ActionReplayService replay;

    public EventsController(IngestionService ingestion, ActionReplayService replay) {
        this.ingestion = ingestion;
        this.replay = replay;
    }

    @PostMapping(
            path = "/events",
            consumes = {"application/json", "application/cloudevents+json"})
    public ResponseEntity<IngestResult> ingest(@RequestBody InboundEventRequest request,
                                               @RequestHeader(value = REPROCESS_HEADER, required = false) String reprocess) {
        // Q-258: una fonte esterna dichiara l'URN urn:loyaltyhub:source:<codice> (docs/05 §2); la forma breve resta
        // ammessa solo ai chiamanti interni (simulatore, scenari, transazioni), che non passano da qui.
        if (request != null && request.source() != null && !request.source().isBlank()
                && request.source().indexOf(':') < 0) {
            throw LhException.badRequest("source deve essere un URN " + LhSource.SOURCE_PREFIX + "<codice>: "
                    + request.source());
        }
        if (reprocess != null && !reprocess.isBlank()) {
            // Solo su comando umano di un ADMIN (docs/servizi/insight-service.md §3: reprocess ruolo ADMIN).
            if (ActorHolder.get().role() != Role.ADMIN) {
                throw LhException.forbiddenRole("Il riprocessa DLQ richiede il ruolo ADMIN");
            }
            Optional<IngestResult> replayed = replay.replay(request, reprocess.trim());
            if (replayed.isPresent()) {
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(replayed.get());
            }
        }
        IngestResult result = ingestion.ingest(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }
}
