package io.loyaltyhub.ingestion.api;

import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.ingestion.application.InboundResolutionService;
import io.loyaltyhub.ingestion.infra.InboundEventRepository;
import io.loyaltyhub.ingestion.infra.InboundEventRepository.InboundRow;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

/**
 * Monitor ingressi (docs/servizi/ingestion-service.md §3, BO-26, F-ING-09): elenco per esito, dettaglio con il
 * CloudEvent completo, <em>Riprova</em> e <em>Abbina a un membro</em> (F-ING-04, M7.4). Le due azioni sono della
 * capacità {@code inbound.handle} (docs/08 §2: ADMIN, CARE).
 */
@RestController
@RequestMapping("/v1/inbound-events")
public class InboundEventsController {

    /** Corpo di {@code POST /v1/inbound-events/{id}/match}. */
    public record MatchRequest(String memberId) {
    }

    /** Dettaglio: la riga del monitor più l'istante dell'evento e il CloudEvent salvato. */
    public record InboundDetail(String id, String eventId, String sourceCode, String typeCode, String subject,
                                String memberId, Instant receivedAt, Instant eventTime, String status,
                                String rejectCode, String rejectDetail, String correlationId, String origin,
                                String resolution, String resolvedBy, Instant resolvedAt, JsonNode payload) {
    }

    private final InboundEventRepository repository;
    private final InboundResolutionService resolution;
    private final ObjectMapper mapper;

    public InboundEventsController(InboundEventRepository repository, InboundResolutionService resolution,
                                   ObjectMapper mapper) {
        this.repository = repository;
        this.resolution = resolution;
        this.mapper = mapper;
    }

    @GetMapping
    public List<InboundRow> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String memberId,
            @RequestParam(defaultValue = "100") int limit) {
        return repository.search(status, source, type, memberId, Math.min(Math.max(limit, 1), 500));
    }

    @GetMapping("/{id}")
    public InboundDetail get(@PathVariable String id) {
        return detail(id);
    }

    /** Solo {@code REJECTED}/{@code UNMATCHED}: rivaluta e, se valido, pubblica; altri stati → {@code 409}. */
    @PostMapping("/{id}/retry")
    @RequiresRole({Role.ADMIN, Role.CARE})
    public InboundDetail retry(@PathVariable String id) {
        resolution.retry(id);
        return detail(id);
    }

    /** Abbina un {@code UNMATCHED} al membro indicato (deve esistere ed essere ACTIVE, altrimenti {@code 422}). */
    @PostMapping("/{id}/match")
    @RequiresRole({Role.ADMIN, Role.CARE})
    public InboundDetail match(@PathVariable String id, @RequestBody(required = false) MatchRequest body) {
        resolution.match(id, body == null ? null : body.memberId());
        return detail(id);
    }

    private InboundDetail detail(String id) {
        InboundEventRepository.StoredInbound s = repository.findStored(id)
                .orElseThrow(() -> LhException.notFound("Evento non trovato: " + id));
        InboundRow r = s.row();
        return new InboundDetail(r.id(), r.eventId(), r.sourceCode(), r.typeCode(), r.subject(), r.memberId(),
                r.receivedAt(), s.eventTime(), r.status(), r.rejectCode(), r.rejectDetail(), r.correlationId(),
                r.origin(), r.resolution(), r.resolvedBy(), r.resolvedAt(),
                s.payloadJson() == null ? null : mapper.readTree(s.payloadJson()));
    }
}
